package com.orderflow;

import com.orderflow.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test — verifies the full Spring context loads successfully
 * with real PostgreSQL, Redis, and Kafka (via Testcontainers).
 */
class OrderFlowApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts without errors
    }
}
