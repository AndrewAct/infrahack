package io.infrahack.ridesharedispatch.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class NotificationDeliveryRepository {

    private final JdbcTemplate jdbc;

    public NotificationDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if a new delivery row was created; false if (event, recipient, channel)
     *          was already delivered -- the caller must not simulate a second send. */
    public boolean insertIfAbsent(UUID notificationId, UUID eventId, UUID recipientId, String channel) {
        int rows = jdbc.update("""
                        INSERT INTO notification_deliveries
                            (notification_id, event_id, recipient_id, channel, status, attempt_count, sent_at)
                        VALUES (?, ?, ?, ?, 'SENT', 1, ?)
                        ON CONFLICT (event_id, recipient_id, channel) DO NOTHING
                        """,
                notificationId, eventId, recipientId, channel, OffsetDateTime.now());
        return rows == 1;
    }
}
