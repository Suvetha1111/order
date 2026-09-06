package com.orderflow.event.publisher;

import com.orderflow.event.entity.OutboxEvent;
import com.orderflow.event.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock private OutboxEventRepository outboxRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher outboxPublisher;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        outboxPublisher = new OutboxPublisher(
                outboxRepository, kafkaTemplate,
                "orderflow.order.events", 50, meterRegistry);
    }

    @Test
    @DisplayName("should do nothing when no unpublished events exist")
    void publishPendingEvents_noneFound() {
        when(outboxRepository.findUnpublishedBatch(50)).thenReturn(Collections.emptyList());

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("should publish event to Kafka and mark as published")
    void publishPendingEvents_success() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent("Order", aggregateId, "ORDER_CREATED", "{\"test\":true}");

        when(outboxRepository.findUnpublishedBatch(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.publishPendingEvents();

        // Verify Kafka send with correct topic and key
        verify(kafkaTemplate).send("orderflow.order.events", aggregateId.toString(), "{\"test\":true}");

        // Verify event marked as published
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().isPublished()).isTrue();
        assertThat(captor.getValue().getPublishedAt()).isNotNull();

        // Verify metrics
        Counter published = meterRegistry.find("outbox.events.published").counter();
        assertThat(published).isNotNull();
        assertThat(published.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should publish multiple events in batch")
    void publishPendingEvents_batchSuccess() {
        OutboxEvent event1 = new OutboxEvent("Order", UUID.randomUUID(), "ORDER_CREATED", "{\"e\":1}");
        OutboxEvent event2 = new OutboxEvent("Order", UUID.randomUUID(), "ORDER_CONFIRMED", "{\"e\":2}");

        when(outboxRepository.findUnpublishedBatch(50)).thenReturn(List.of(event1, event2));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("should stop batch on Kafka send failure and not mark as published")
    void publishPendingEvents_kafkaFailure() {
        OutboxEvent event1 = new OutboxEvent("Order", UUID.randomUUID(), "ORDER_CREATED", "{\"e\":1}");
        OutboxEvent event2 = new OutboxEvent("Order", UUID.randomUUID(), "ORDER_CONFIRMED", "{\"e\":2}");

        when(outboxRepository.findUnpublishedBatch(50)).thenReturn(List.of(event1, event2));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));

        outboxPublisher.publishPendingEvents();

        // First event fails → should not be marked published
        verify(outboxRepository, never()).save(any(OutboxEvent.class));

        // Should break after first failure — second event not attempted
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());

        // Verify failure metric
        Counter failed = meterRegistry.find("outbox.events.failed").counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }
}
