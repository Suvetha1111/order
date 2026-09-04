package com.orderflow.event.publisher;

import com.orderflow.event.entity.OutboxEvent;
import com.orderflow.event.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls the outbox table and publishes unpublished events to Kafka.
 *
 * This is the "relay" side of the transactional outbox pattern:
 *
 *   1. Poll outbox_events WHERE published = false (ordered by created_at)
 *   2. Send each event to Kafka (using aggregate_id as the message key
 *      for partition ordering per order)
 *   3. Mark each event as published in the same transaction
 *
 * Why poll instead of CDC (Change Data Capture)?
 * CDC with Debezium is the production-grade approach, but polling is
 * simpler to set up, understand, and debug. For a portfolio project
 * demonstrating the pattern, polling is the right trade-off.
 *
 * Guarantees:
 * - At-least-once delivery: if the app crashes after sending to Kafka
 *   but before marking published, the event will be re-sent on the next
 *   poll. Consumers must be idempotent (they are, via processed_events).
 * - Ordering: events for the same order go to the same Kafka partition
 *   (keyed by aggregateId), so they are consumed in order.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String orderEventsTopic;
    private final int batchSize;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxPublisher(OutboxEventRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${orderflow.kafka.topics.order-events}") String orderEventsTopic,
                           @Value("${orderflow.outbox.batch-size}") int batchSize,
                           MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.orderEventsTopic = orderEventsTopic;
        this.batchSize = batchSize;
        this.publishedCounter = Counter.builder("outbox.events.published")
                .description("Number of outbox events published to Kafka")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("outbox.events.failed")
                .description("Number of outbox events that failed to publish")
                .register(meterRegistry);
    }

    /**
     * Polls the outbox table at a fixed interval and publishes events to Kafka.
     * Each batch is processed in a single transaction — if the app crashes
     * mid-batch, unpublished events will be retried on the next poll.
     */
    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval-ms}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findUnpublishedBatch(batchSize);
        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox publisher: found {} unpublished events", events.size());

        for (OutboxEvent event : events) {
            try {
                // Use aggregateId as key → same order always goes to same partition
                kafkaTemplate.send(
                        orderEventsTopic,
                        event.getAggregateId().toString(),
                        event.getPayload()
                ).get();  // Block to ensure delivery before marking published

                event.markPublished();
                outboxRepository.save(event);
                publishedCounter.increment();

                log.debug("Published outbox event: id={}, type={}, aggregateId={}",
                        event.getId(), event.getEventType(), event.getAggregateId());

            } catch (Exception e) {
                failedCounter.increment();
                log.error("Failed to publish outbox event: id={}, type={}, aggregateId={}",
                        event.getId(), event.getEventType(), event.getAggregateId(), e);
                // Don't mark as published — will be retried on next poll
                // Break to avoid reordering: events for the same aggregate
                // must be published in order
                break;
            }
        }
    }
}
