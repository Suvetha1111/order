package com.orderflow.event.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.entity.ProcessedEvent;
import com.orderflow.event.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base class for idempotent Kafka consumers.
 *
 * Idempotent processing flow:
 *   1. Deserialize the event payload
 *   2. Check processed_events table — skip if already processed
 *   3. Execute consumer-specific business logic (template method)
 *   4. Record the event as processed
 *   5. Acknowledge the Kafka offset (manual ack)
 *
 * Why manual acknowledgment?
 * With auto-commit, offsets are committed on a timer regardless of
 * whether processing succeeded. Manual ack ensures we only commit
 * after the business logic + idempotency record are durably written.
 *
 * Why DB-level idempotency instead of just relying on Kafka offsets?
 * Because consumer rebalances, crashes, and the at-least-once outbox
 * publisher can all cause re-delivery. The processed_events table
 * is the authoritative record of what has been handled.
 */
public abstract class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    protected IdempotentConsumer(ProcessedEventRepository processedEventRepository,
                                ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Each consumer has a unique group name for the processed_events table.
     * This allows multiple consumers to independently process the same event.
     */
    protected abstract String consumerGroup();

    /**
     * Template method — implement the consumer-specific business logic.
     * Called only if the event has not been processed by this consumer group.
     */
    protected abstract void handleEvent(OrderEvent event);

    /**
     * Entry point called by the @KafkaListener. Handles deserialization,
     * idempotency check, delegation, and acknowledgment.
     */
    @Transactional
    public void consume(String payload, Acknowledgment ack) {
        OrderEvent event;
        try {
            event = objectMapper.readValue(payload, OrderEvent.class);
        } catch (JsonProcessingException e) {
            log.error("[{}] Failed to deserialize event, sending to DLT: {}",
                    consumerGroup(), e.getMessage());
            // Acknowledge to avoid infinite retry of poison pill messages.
            // The DLT handling is done at the Kafka listener container level.
            ack.acknowledge();
            throw new IllegalArgumentException("Invalid event payload", e);
        }

        // Idempotency check
        if (processedEventRepository.existsByEventIdAndConsumerGroup(
                event.eventId(), consumerGroup())) {
            log.info("[{}] Skipping already processed event: eventId={}, type={}",
                    consumerGroup(), event.eventId(), event.eventType());
            ack.acknowledge();
            return;
        }

        log.info("[{}] Processing event: eventId={}, type={}, orderId={}",
                consumerGroup(), event.eventId(), event.eventType(), event.orderId());

        // Delegate to concrete consumer
        handleEvent(event);

        // Record as processed (same transaction as business logic)
        processedEventRepository.save(new ProcessedEvent(event.eventId(), consumerGroup()));

        ack.acknowledge();

        log.debug("[{}] Event processed and acknowledged: eventId={}",
                consumerGroup(), event.eventId());
    }
}
