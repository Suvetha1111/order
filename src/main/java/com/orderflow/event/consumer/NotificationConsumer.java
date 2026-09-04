package com.orderflow.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Simulates sending notifications for order lifecycle events.
 *
 * In a production system, this would:
 * - Send emails via SendGrid / SES
 * - Push mobile notifications via FCM / APNS
 * - Post to Slack / webhook endpoints
 *
 * For this project, it logs the notification that would be sent,
 * demonstrating the consumer pattern and event routing without
 * requiring an external email/notification provider.
 *
 * Handles ALL event types — every order state change triggers
 * a notification to the user.
 */
@Component
public class NotificationConsumer extends IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final String CONSUMER_GROUP = "notification-consumer";

    private final Counter notificationsSent;

    public NotificationConsumer(ProcessedEventRepository processedEventRepository,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        super(processedEventRepository, objectMapper);
        this.notificationsSent = Counter.builder("notifications.sent")
                .description("Number of notifications sent")
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
        String message = buildNotificationMessage(event);

        // In production: send email/push notification here
        log.info("[{}] NOTIFICATION → userId={}, orderId={}: {}",
                consumerGroup(), event.userId(), event.orderId(), message);

        notificationsSent.increment();
    }

    private String buildNotificationMessage(OrderEvent event) {
        return switch (event.eventType()) {
            case OrderEvent.ORDER_CREATED ->
                    "Your order #%s has been placed. Total: $%s".formatted(
                            shortId(event.orderId()), event.totalAmount());
            case OrderEvent.ORDER_CONFIRMED ->
                    "Payment confirmed for order #%s. Preparing for shipment.".formatted(
                            shortId(event.orderId()));
            case OrderEvent.ORDER_SHIPPED ->
                    "Order #%s has been shipped!".formatted(shortId(event.orderId()));
            case OrderEvent.ORDER_DELIVERED ->
                    "Order #%s has been delivered. Enjoy!".formatted(shortId(event.orderId()));
            case OrderEvent.ORDER_CANCELLED ->
                    "Order #%s has been cancelled. Any charges will be refunded.".formatted(
                            shortId(event.orderId()));
            case OrderEvent.ORDER_FAILED ->
                    "Order #%s could not be processed. Please try again or contact support.".formatted(
                            shortId(event.orderId()));
            default ->
                    "Order #%s status updated to %s.".formatted(
                            shortId(event.orderId()), event.orderStatus());
        };
    }

    /** First 8 chars of UUID for human-readable notification messages. */
    private String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
