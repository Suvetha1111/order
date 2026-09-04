package com.orderflow.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.repository.ProcessedEventRepository;
import com.orderflow.product.service.InventoryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Handles inventory lifecycle events triggered by order state changes.
 *
 * Event handling:
 *   ORDER_CONFIRMED → confirm shipment (deduct reserved → reduce quantity)
 *   ORDER_FAILED    → release reservation (reserved -= quantity)
 *   ORDER_CANCELLED → release reservation (handled by OrderService directly,
 *                     but this consumer handles async cancellations if any)
 *
 * Note: Initial reservation happens synchronously in OrderService.createOrder()
 * (inside the same transaction). This consumer handles the CONFIRMATION step —
 * converting the reservation into an actual deduction once payment clears.
 *
 * The two-field inventory model (quantity/reserved) supports this flow:
 *   1. createOrder → reserve(amount): reserved += amount
 *   2. Payment confirmed (this consumer) → confirmShipment(amount):
 *      quantity -= amount, reserved -= amount
 *   3. Payment failed (this consumer) → releaseStock(amount): reserved -= amount
 */
@Component
public class InventoryConsumer extends IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryConsumer.class);
    private static final String CONSUMER_GROUP = "inventory-consumer";

    private final InventoryService inventoryService;
    private final Counter confirmations;
    private final Counter releases;

    public InventoryConsumer(ProcessedEventRepository processedEventRepository,
                             ObjectMapper objectMapper,
                             InventoryService inventoryService,
                             MeterRegistry meterRegistry) {
        super(processedEventRepository, objectMapper);
        this.inventoryService = inventoryService;
        this.confirmations = Counter.builder("inventory.confirmations")
                .description("Number of inventory shipments confirmed")
                .register(meterRegistry);
        this.releases = Counter.builder("inventory.releases")
                .description("Number of inventory reservations released")
                .register(meterRegistry);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @KafkaListener(
            topics = "${orderflow.kafka.topics.order-events}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(@Payload String payload, Acknowledgment ack) {
        consume(payload, ack);
    }

    @Override
    protected void handleEvent(OrderEvent event) {
        switch (event.eventType()) {
            case OrderEvent.ORDER_CONFIRMED -> handleConfirmed(event);
            case OrderEvent.ORDER_FAILED, OrderEvent.ORDER_CANCELLED -> handleRelease(event);
            default -> log.debug("[{}] Ignoring event type: {}", consumerGroup(), event.eventType());
        }
    }

    /**
     * Order confirmed (payment cleared) — convert reservations to actual deductions.
     */
    private void handleConfirmed(OrderEvent event) {
        log.info("[{}] Confirming inventory for orderId={}", consumerGroup(), event.orderId());

        for (OrderEvent.OrderEventItem item : event.items()) {
            inventoryService.confirmShipment(item.productId(), item.quantity());
            log.debug("[{}] Confirmed shipment: productId={}, quantity={}",
                    consumerGroup(), item.productId(), item.quantity());
        }

        confirmations.increment();
        log.info("[{}] Inventory confirmed for orderId={}, items={}",
                consumerGroup(), event.orderId(), event.items().size());
    }

    /**
     * Order failed or cancelled — release the reserved stock back to available.
     */
    private void handleRelease(OrderEvent event) {
        log.info("[{}] Releasing inventory for orderId={} (reason: {})",
                consumerGroup(), event.orderId(), event.eventType());

        for (OrderEvent.OrderEventItem item : event.items()) {
            inventoryService.releaseStock(item.productId(), item.quantity());
            log.debug("[{}] Released stock: productId={}, quantity={}",
                    consumerGroup(), item.productId(), item.quantity());
        }

        releases.increment();
        log.info("[{}] Inventory released for orderId={}, items={}",
                consumerGroup(), event.orderId(), event.items().size());
    }
}
