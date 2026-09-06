package com.orderflow.event.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.entity.OutboxEvent;
import com.orderflow.event.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock private OutboxEventRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxService outboxService;

    @Test
    @DisplayName("should serialize event and save to outbox")
    void saveOrderEvent_success() throws JsonProcessingException {
        // Arrange
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(
                UUID.randomUUID(),
                OrderEvent.ORDER_CREATED,
                orderId,
                userId,
                Instant.now(),
                1,
                new BigDecimal("59.98"),
                "PENDING",
                List.of(new OrderEvent.OrderEventItem(
                        UUID.randomUUID(), "Widget", 2, new BigDecimal("29.99")
                ))
        );

        String expectedJson = "{\"eventType\":\"ORDER_CREATED\"}";
        when(objectMapper.writeValueAsString(event)).thenReturn(expectedJson);
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        outboxService.saveOrderEvent(event);

        // Assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getAggregateType()).isEqualTo("Order");
        assertThat(saved.getAggregateId()).isEqualTo(orderId);
        assertThat(saved.getEventType()).isEqualTo(OrderEvent.ORDER_CREATED);
        assertThat(saved.getPayload()).isEqualTo(expectedJson);
        assertThat(saved.isPublished()).isFalse();
    }

    @Test
    @DisplayName("should throw when serialization fails")
    void saveOrderEvent_serializationFailure() throws JsonProcessingException {
        OrderEvent event = new OrderEvent(
                UUID.randomUUID(),
                OrderEvent.ORDER_CREATED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                1,
                BigDecimal.TEN,
                "PENDING",
                List.of()
        );

        when(objectMapper.writeValueAsString(event))
                .thenThrow(new JsonProcessingException("bad") {});

        assertThatThrownBy(() -> outboxService.saveOrderEvent(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize order event");

        verify(outboxRepository, never()).save(any());
    }
}
