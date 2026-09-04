package com.orderflow.event.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.entity.OutboxEvent;
import com.orderflow.event.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes events to the outbox table within the SAME transaction
 * as the business operation.
 *
 * This is the "write" side of the transactional outbox pattern:
 *
 *   BEGIN TRANSACTION
 *     1. Insert/update order in orders table
 *     2. Insert event in outbox_events table  ← this service
 *   COMMIT
 *
 * The event is guaranteed to be written if and only if the business
 * operation succeeds. A separate OutboxPublisher (Phase 5) polls
 * unpublished events and sends them to Kafka.
 *
 * Why not publish to Kafka directly in the transaction?
 * Because Kafka is not part of the DB transaction. If the app crashes
 * after publishing but before committing, the event is sent but the
 * order doesn't exist — or vice versa. The outbox makes the event
 * part of the atomic DB transaction, then delivers it asynchronously.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Save an order event to the outbox within the current transaction.
     * Uses MANDATORY propagation to enforce that a transaction already exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveOrderEvent(OrderEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order event", e);
        }

        OutboxEvent outboxEvent = new OutboxEvent(
                "Order",
                event.orderId(),
                event.eventType(),
                payload
        );

        outboxRepository.save(outboxEvent);

        log.debug("Outbox event saved: eventType={}, orderId={}, eventId={}",
                event.eventType(), event.orderId(), outboxEvent.getId());
    }
}
