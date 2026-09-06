package com.orderflow.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.entity.ProcessedEvent;
import com.orderflow.event.repository.ProcessedEventRepository;
import com.orderflow.product.service.InventoryService;
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
class InventoryConsumerTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private InventoryService inventoryService;
    @Mock private Acknowledgment ack;

    private InventoryConsumer inventoryConsumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        inventoryConsumer = new InventoryConsumer(
                processedEventRepository, objectMapper,
                inventoryService, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("should confirm shipment for each item on ORDER_CONFIRMED")
    void handleEvent_orderConfirmed() throws Exception {
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        OrderEvent event = createEvent(OrderEvent.ORDER_CONFIRMED, List.of(
                new OrderEvent.OrderEventItem(productId1, "Widget A", 2, new BigDecimal("10.00")),
                new OrderEvent.OrderEventItem(productId2, "Widget B", 3, new BigDecimal("20.00"))
        ));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "inventory-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        inventoryConsumer.listen(payload, ack);

        verify(inventoryService).confirmShipment(productId1, 2);
        verify(inventoryService).confirmShipment(productId2, 3);
        verify(inventoryService, never()).releaseStock(any(), anyInt());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should release stock for each item on ORDER_FAILED")
    void handleEvent_orderFailed() throws Exception {
        UUID productId = UUID.randomUUID();
        OrderEvent event = createEvent(OrderEvent.ORDER_FAILED, List.of(
                new OrderEvent.OrderEventItem(productId, "Widget", 5, new BigDecimal("15.00"))
        ));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "inventory-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        inventoryConsumer.listen(payload, ack);

        verify(inventoryService).releaseStock(productId, 5);
        verify(inventoryService, never()).confirmShipment(any(), anyInt());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should release stock on ORDER_CANCELLED")
    void handleEvent_orderCancelled() throws Exception {
        UUID productId = UUID.randomUUID();
        OrderEvent event = createEvent(OrderEvent.ORDER_CANCELLED, List.of(
                new OrderEvent.OrderEventItem(productId, "Widget", 1, new BigDecimal("10.00"))
        ));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "inventory-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        inventoryConsumer.listen(payload, ack);

        verify(inventoryService).releaseStock(productId, 1);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should ignore ORDER_CREATED events")
    void handleEvent_ignoresCreated() throws Exception {
        OrderEvent event = createEvent(OrderEvent.ORDER_CREATED, List.of(
                new OrderEvent.OrderEventItem(UUID.randomUUID(), "Widget", 1, new BigDecimal("10.00"))
        ));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "inventory-consumer"))
                .thenReturn(false);
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        inventoryConsumer.listen(payload, ack);

        verify(inventoryService, never()).confirmShipment(any(), anyInt());
        verify(inventoryService, never()).releaseStock(any(), anyInt());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should skip already processed events (idempotency)")
    void handleEvent_alreadyProcessed() throws Exception {
        OrderEvent event = createEvent(OrderEvent.ORDER_CONFIRMED, List.of(
                new OrderEvent.OrderEventItem(UUID.randomUUID(), "Widget", 1, new BigDecimal("10.00"))
        ));
        String payload = objectMapper.writeValueAsString(event);

        when(processedEventRepository.existsByEventIdAndConsumerGroup(event.eventId(), "inventory-consumer"))
                .thenReturn(true);

        inventoryConsumer.listen(payload, ack);

        verify(inventoryService, never()).confirmShipment(any(), anyInt());
        verify(inventoryService, never()).releaseStock(any(), anyInt());
        verify(ack).acknowledge();
    }

    private OrderEvent createEvent(String eventType, List<OrderEvent.OrderEventItem> items) {
        return new OrderEvent(
                UUID.randomUUID(),
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                1,
                new BigDecimal("100.00"),
                "PENDING",
                items
        );
    }
}
