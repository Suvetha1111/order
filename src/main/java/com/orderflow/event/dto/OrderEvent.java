package com.orderflow.event.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka event payload for order-related events.
 *
 * Every event carries:
 * - eventId:    unique ID for idempotent processing
 * - eventType:  discriminator for consumer routing
 * - orderId:    the aggregate this event belongs to
 * - timestamp:  when the event was created
 * - version:    event schema version (for future evolution)
 * - payload:    event-specific data
 *
 * This is a "fat event" — it carries enough data for consumers
 * to act without querying back to the orders service.
 */
public record OrderEvent(
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID userId,
        Instant timestamp,
        int version,
        BigDecimal totalAmount,
        String orderStatus,
        List<OrderEventItem> items
) {
    public record OrderEventItem(
            UUID productId,
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {}

    /** Event type constants */
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_FAILED = "ORDER_FAILED";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";
}
