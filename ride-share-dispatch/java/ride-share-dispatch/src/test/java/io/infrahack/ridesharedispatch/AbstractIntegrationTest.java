package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Agent;
import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Objects;

/**
 * Real Postgres, Redis, and Kafka via Testcontainers -- no mocks. This module's whole
 * point is to prove races and durability guarantees; a mocked reservation store or a
 * mocked JdbcTemplate would just assert that the mock does what the mock was told to
 * do. Containers are started once per JVM (static, never stopped -- Ryuk reaps them
 * when the test run ends) so a full suite does not pay Kafka's ~15s startup cost once
 * per test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ridesharedispatch_test")
                    .withUsername("ridesharedispatch")
                    .withPassword("ridesharedispatch");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected AgentService agentService;

    /** Every test starts from a clean Postgres and Redis so tests can run in any order
     *  and assert on exact row counts. */
    @BeforeEach
    void resetState() {
        jdbc.update("TRUNCATE TABLE outbox_events, processed_events, notification_deliveries, "
                + "fake_payment_provider_charges, payments, assignments, dispatch_offers, "
                + "dispatch_requests, agents RESTART IDENTITY CASCADE");
        try (RedisConnection connection = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    /** Registers a durable agent profile, marks it available, and pings a fresh location --
     *  the minimum state a candidate needs to be matchable. */
    protected AgentId createAvailableAgentAt(GeoPoint point) {
        Agent agent = agentService.register("Test Agent", "STANDARD");
        agentService.setAvailability(agent.id(), true);
        agentService.recordLocation(agent.id(), point, 1L, Instant.now());
        return agent.id();
    }
}
