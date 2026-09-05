package com.orderflow.config;

import com.orderflow.event.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom business metrics exposed to Prometheus via Micrometer.
 *
 * Metrics registered here:
 *
 *   Gauges:
 *   - outbox.pending.count   — number of unpublished outbox events (outbox lag)
 *
 *   Timers (used via @Timed or injected):
 *   - order.creation.time    — time to create an order (end-to-end)
 *   - order.cancellation.time — time to cancel an order
 *
 * Counters for published/failed events and payment/inventory outcomes
 * are registered directly in their respective services (OutboxPublisher,
 * PaymentConsumer, InventoryConsumer) to keep metrics close to the code
 * that increments them.
 *
 * Why custom metrics?
 * Spring Boot Actuator + Micrometer auto-instruments HTTP request
 * latency, JVM stats, and Hikari pool metrics. These custom metrics
 * track domain-specific signals that SREs and developers actually
 * alert on: outbox lag (event delivery health), order processing
 * times (business SLA), and consumer throughput.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Gauge outboxPendingGauge(MeterRegistry registry,
                                    OutboxEventRepository outboxRepository) {
        return Gauge.builder("outbox.pending.count", outboxRepository,
                        repo -> repo.findUnpublishedBatch(1).size() > 0 ?
                                repo.findUnpublishedBatch(Integer.MAX_VALUE).size() : 0)
                .description("Number of unpublished outbox events (outbox lag)")
                .register(registry);
    }

    @Bean
    public Timer orderCreationTimer(MeterRegistry registry) {
        return Timer.builder("order.creation.time")
                .description("Time to create an order")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer orderCancellationTimer(MeterRegistry registry) {
        return Timer.builder("order.cancellation.time")
                .description("Time to cancel an order")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
