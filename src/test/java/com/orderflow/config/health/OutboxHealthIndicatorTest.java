package com.orderflow.config.health;

import com.orderflow.event.entity.OutboxEvent;
import com.orderflow.event.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxHealthIndicatorTest {

    @Mock private OutboxEventRepository outboxRepository;
    @InjectMocks private OutboxHealthIndicator healthIndicator;

    @Test
    @DisplayName("should report UP when no pending events")
    void health_noPending() {
        when(outboxRepository.findUnpublishedBatch(anyInt())).thenReturn(Collections.emptyList());

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("pending_events")).isEqualTo(0);
    }

    @Test
    @DisplayName("should report UP with low pending count")
    void health_lowPending() {
        List<OutboxEvent> events = createEvents(5);
        when(outboxRepository.findUnpublishedBatch(anyInt())).thenReturn(events);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("pending_events")).isEqualTo(5);
        assertThat(health.getDetails()).doesNotContainKey("status");
    }

    @Test
    @DisplayName("should report DEGRADED when pending exceeds threshold")
    void health_degraded() {
        List<OutboxEvent> events = createEvents(50);
        when(outboxRepository.findUnpublishedBatch(anyInt())).thenReturn(events);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("status")).isEqualTo("DEGRADED");
        assertThat(health.getDetails().get("pending_events")).isEqualTo(50);
    }

    @Test
    @DisplayName("should report DOWN when pending exceeds unhealthy threshold")
    void health_down() {
        List<OutboxEvent> events = createEvents(101);
        when(outboxRepository.findUnpublishedBatch(anyInt())).thenReturn(events);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("pending_events")).isEqualTo(101);
    }

    private List<OutboxEvent> createEvents(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new OutboxEvent("Order", UUID.randomUUID(), "ORDER_CREATED", "{}"))
                .toList();
    }
}
