package io.infrahack.ridesharedispatch.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Per-consumer dedup ledger. Kafka only promises at-least-once delivery, so every
 * consumer must be prepared to see the same event again after a rebalance or a retry.
 * {@code markProcessed} returns whether THIS call was the one that actually recorded
 * the event -- the caller only performs its business side effect when it was.
 */
@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this call won the race and should perform the side effect;
     *          false if {@code (eventId, consumerName)} was already processed. */
    public boolean markProcessed(UUID eventId, String consumerName) {
        int rows = jdbc.update("""
                        INSERT INTO processed_events (event_id, consumer_name) VALUES (?, ?)
                        ON CONFLICT DO NOTHING
                        """,
                eventId, consumerName);
        return rows == 1;
    }
}
