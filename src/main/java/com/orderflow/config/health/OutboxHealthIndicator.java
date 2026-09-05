package com.orderflow.config.health;

import com.orderflow.event.entity.OutboxEvent;
import com.orderflow.event.repository.OutboxEventRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Custom health indicator for the transactional outbox.
 *
 * Reports DOWN if there are more than 100 unpublished events,
 * which indicates the outbox publisher is unable to keep up
 * or Kafka is unavailable.
 *
 * Reports DEGRADED (UP with warning) if 10-100 events are pending.
 *
 * This gives operators early warning before the outbox backs up
 * to the point where it causes DB growth or delivery delays.
 *
 * Exposed at: GET /actuator/health (when show-details is enabled)
 */
@Component
public class OutboxHealthIndicator implements HealthIndicator {

    private static final int DEGRADED_THRESHOLD = 10;
    private static final int UNHEALTHY_THRESHOLD = 100;

    private final OutboxEventRepository outboxRepository;

    public OutboxHealthIndicator(OutboxEventRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public Health health() {
        List<OutboxEvent> pending = outboxRepository.findUnpublishedBatch(UNHEALTHY_THRESHOLD + 1);
        int pendingCount = pending.size();

        if (pendingCount > UNHEALTHY_THRESHOLD) {
            return Health.down()
                    .withDetail("pending_events", pendingCount)
                    .withDetail("threshold", UNHEALTHY_THRESHOLD)
                    .withDetail("message", "Outbox lag exceeds threshold — Kafka may be unavailable")
                    .build();
        }

        if (pendingCount > DEGRADED_THRESHOLD) {
            return Health.up()
                    .withDetail("pending_events", pendingCount)
                    .withDetail("status", "DEGRADED")
                    .withDetail("message", "Outbox lag elevated — monitor closely")
                    .build();
        }

        return Health.up()
                .withDetail("pending_events", pendingCount)
                .build();
    }
}
