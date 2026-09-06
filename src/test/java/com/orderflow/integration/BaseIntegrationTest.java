package com.orderflow.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests with Testcontainers.
 *
 * Uses the "singleton container" pattern: containers are started once
 * in a static initializer and reused across ALL test classes in the
 * JVM. This avoids the lifecycle mismatch between Spring context
 * caching and per-class @Testcontainers container management.
 *
 * Starts real instances of:
 *   - PostgreSQL 16 (same version as production docker-compose)
 *   - Redis 7
 *   - Kafka (Apache Kafka, Testcontainers 2.x default)
 *
 * The test profile (application-test.yml) uses ddl-auto: create-drop
 * so the schema is created from JPA entities, not the SQL migration.
 * This validates that our entities match the migration schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    static final PostgreSQLContainer<?> postgres;
    static final GenericContainer<?> redis;
    static final KafkaContainer kafka;

    static {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("orderflow_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();

        redis = new GenericContainer<>(
                DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();

        kafka = new KafkaContainer(
                DockerImageName.parse("apache/kafka:3.7.0"));
        kafka.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // Disable outbox polling in tests (we trigger it manually)
        registry.add("orderflow.outbox.poll-interval-ms", () -> "999999999");
    }
}
