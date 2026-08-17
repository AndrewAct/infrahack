package io.infrahack.ridesharedispatch.repository;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transactional outbox table. {@link #append} is called from inside the same
 * {@code @Transactional} method that changes durable state (e.g. AssignmentService.complete),
 * so "the state changed" and "an event describing it exists" commit or roll back together --
 * there is never a window where one happened and the other did not. Publishing to Kafka is a
 * separate, independently-retryable step (see infrastructure.kafka.OutboxPublisher).
 */
@Repository
public class OutboxRepository {

    private static final RowMapper<PendingEvent> ROW_MAPPER = (rs, rowNum) -> new PendingEvent(
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("payload"));

    public record PendingEvent(UUID eventId, String eventType, UUID aggregateId, String payloadJson) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(String eventType, UUID aggregateId, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        jdbc.update("""
                        INSERT INTO outbox_events (event_id, event_type, aggregate_id, payload)
                        VALUES (?, ?, ?, ?::jsonb)
                        """,
                UUID.randomUUID(), eventType, aggregateId, json);
    }

    /**
     * Atomically claims a bounded batch with short-lived leases. The row locks exist only
     * for this statement; Kafka I/O happens after the transaction has ended. A crashed
     * publisher leaves leases that become claimable again after {@code claimUntil}.
     * Publication is therefore at least once, so consumers must deduplicate by event ID.
     */
    public List<PendingEvent> claimUnpublishedBatch(UUID workerId, int limit, java.time.Instant claimUntil) {
        return jdbc.query("""
                        WITH candidates AS (
                          SELECT event_id FROM outbox_events
                          WHERE published_at IS NULL
                            AND (claim_until IS NULL OR claim_until < now())
                          ORDER BY created_at
                          LIMIT ?
                          FOR UPDATE SKIP LOCKED
                        )
                        UPDATE outbox_events e
                        SET claimed_by = ?, claim_until = ?
                        FROM candidates c
                        WHERE e.event_id = c.event_id
                        RETURNING e.event_id, e.event_type, e.aggregate_id, e.payload::text AS payload
                        """, ROW_MAPPER, limit, workerId,
                OffsetDateTime.ofInstant(claimUntil, java.time.ZoneOffset.UTC));
    }

    public void markPublished(UUID eventId, UUID workerId) {
        jdbc.update("""
                        UPDATE outbox_events
                        SET published_at = ?, claimed_by = NULL, claim_until = NULL
                        WHERE event_id = ? AND claimed_by = ?
                        """, OffsetDateTime.now(), eventId, workerId);
    }

    public void releaseClaim(UUID eventId, UUID workerId) {
        jdbc.update("""
                        UPDATE outbox_events SET claimed_by = NULL, claim_until = NULL
                        WHERE event_id = ? AND claimed_by = ? AND published_at IS NULL
                        """, eventId, workerId);
    }

    public int countPending() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
        return count == null ? 0 : count;
    }
}
