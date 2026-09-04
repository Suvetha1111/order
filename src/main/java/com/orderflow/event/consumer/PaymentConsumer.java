package com.orderflow.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.repository.ProcessedEventRepository;
import com.orderflow.order.entity.OrderStatus;
import com.orderflow.order.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Simulates payment processing for new orders.
 *
 * On ORDER_CREATED:
 *   - Simulates a payment gateway call
 *   - If "payment succeeds" → transitions order to CONFIRMED
 *   - If "payment fails" → transitions order to FAILED
 *
 * Payment simulation rules (deterministic for testing):
 *   - Orders with totalAmount > 10,000 → fail (simulates fraud check)
 *   - All other orders → succeed
 *
 * In a real system, this would call a payment gateway (Stripe, etc.)
 * and handle webhooks for async payment confirmation.
 */
@Component
public class PaymentConsumer extends IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);
    private static final BigDecimal FRAUD_THRESHOLD = new BigDecimal("10000.00");
    private static final String CONSUMER_GROUP = "payment-consumer";

    private final OrderService orderService;
    private final Counter paymentsProcessed;
    private final Counter paymentsFailed;

    public PaymentConsumer(ProcessedEventRepository processedEventRepository,
                           ObjectMapper objectMapper,
                           OrderService orderService,
                           MeterRegistry meterRegistry) {
        super(processedEventRepository, objectMapper);
        this.orderService = orderService;
        this.paymentsProcessed = Counter.builder("payments.processed")
                .description("Number of payments successfully processed")
                .register(meterRegistry);
        this.paymentsFailed = Counter.builder("payments.failed")
                .description("Number of payments that failed")
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
        if (!OrderEvent.ORDER_CREATED.equals(event.eventType())) {
            log.debug("[{}] Ignoring event type: {}", consumerGroup(), event.eventType());
            return;
        }

        log.info("[{}] Processing payment for orderId={}, amount={}",
                consumerGroup(), event.orderId(), event.totalAmount());

        // Simulate payment processing
        boolean paymentSuccess = simulatePayment(event);

        if (paymentSuccess) {
            orderService.transitionOrder(event.orderId(), OrderStatus.CONFIRMED);
            paymentsProcessed.increment();
            log.info("[{}] Payment succeeded for orderId={}", consumerGroup(), event.orderId());
        } else {
            orderService.transitionOrder(event.orderId(), OrderStatus.FAILED);
            paymentsFailed.increment();
            log.warn("[{}] Payment failed for orderId={} (amount {} exceeds fraud threshold)",
                    consumerGroup(), event.orderId(), event.totalAmount());
        }
    }

    /**
     * Simulates payment processing. Deterministic rules:
     * - Amount > 10,000 → rejected (simulated fraud check)
     * - Everything else → approved
     */
    private boolean simulatePayment(OrderEvent event) {
        // Simulate processing delay would go here in a real system
        return event.totalAmount().compareTo(FRAUD_THRESHOLD) <= 0;
    }
}
