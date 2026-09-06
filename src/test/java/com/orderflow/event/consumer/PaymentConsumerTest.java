package com.orderflow.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.entity.ProcessedEvent;
import com.orderflow.event.repository.ProcessedEventRepository;
import com.orderflow.order.entity.OrderStatus;
import com.orderflow.order.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentConsumerTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private OrderService orderService;
    @Mock private Acknowledgment ack;

    private PaymentConsumer paymentConsumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        paymentConsumer = new PaymentConsumer(
                processedEventRepository, objectMapper,
                orderService, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("should confirm order when payment amount is below fraud threshold")
    void handleEvent_paymentSuccess() throws Exception {
        OrderEvent event = createEvent(OrderEvent.ORDER_CREATED, new BigDecimal("99.99"));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "payment-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        paymentConsumer.listen(payload, ack);

        verify(orderService).transitionOrder(event.orderId(), OrderStatus.CONFIRMED);
        verify(orderService, never()).transitionOrder(any(), eq(OrderStatus.FAILED));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should fail order when payment amount exceeds fraud threshold")
    void handleEvent_paymentFailed() throws Exception {
        OrderEvent event = createEvent(OrderEvent.ORDER_CREATED, new BigDecimal("15000.00"));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "payment-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        paymentConsumer.listen(payload, ack);

        verify(orderService).transitionOrder(event.orderId(), OrderStatus.FAILED);
        verify(orderService, never()).transitionOrder(any(), eq(OrderStatus.CONFIRMED));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should skip already processed events (idempotency)")
    void handleEvent_alreadyProcessed() throws Exception {
        OrderEvent event = createEvent(OrderEvent.ORDER_CREATED, new BigDecimal("50.00"));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "payment-consumer"))
                .thenReturn(true);

        paymentConsumer.listen(payload, ack);

        verify(orderService, never()).transitionOrder(any(), any());
        verify(processedEventRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should ignore non-ORDER_CREATED events")
    void handleEvent_ignoresOtherEventTypes() throws Exception {
        OrderEvent event = createEvent(OrderEvent.ORDER_CONFIRMED, new BigDecimal("50.00"));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "payment-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        paymentConsumer.listen(payload, ack);

        verify(orderService, never()).transitionOrder(any(), any());
        verify(ack).acknowledge();
    }

    private OrderEvent createEvent(String eventType, BigDecimal totalAmount) {
        return new OrderEvent(
                UUID.randomUUID(),
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                1,
                totalAmount,
                "PENDING",
                List.of(new OrderEvent.OrderEventItem(
                        UUID.randomUUID(), "Test Product", 1, new BigDecimal("29.99")
                ))
        );
    }
}
