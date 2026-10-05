package io.infrahack.ridesharedispatch.repository;

import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.domain.Money;
import io.infrahack.ridesharedispatch.domain.Payment;
import io.infrahack.ridesharedispatch.domain.PaymentId;
import io.infrahack.ridesharedispatch.domain.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepository {

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> new Payment(
            PaymentId.of(rs.getObject("payment_id", UUID.class)),
            AssignmentId.of(rs.getObject("assignment_id", UUID.class)),
            rs.getString("operation_id"), Money.ofCents(rs.getLong("amount_cents")),
            PaymentStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(Payment payment) {
        int rows = jdbc.update("""
                        INSERT INTO payments
                          (payment_id, assignment_id, operation_id, amount_cents, status,
                           attempt_count, next_attempt_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_id) DO NOTHING
                        """,
                payment.id().value(), payment.assignmentId().value(), payment.operationId(),
                payment.amount().cents(), payment.status().name(), payment.attemptCount(),
                OffsetDateTime.ofInstant(payment.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(payment.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(payment.updatedAt(), ZoneOffset.UTC));
        return rows == 1;
    }

    public Optional<Payment> findByOperationId(String operationId) {
        return jdbc.query("SELECT * FROM payments WHERE operation_id = ?", ROW_MAPPER, operationId)
                .stream().findFirst();
    }

    /** Atomically claims a bounded due batch; no DB lock is held during provider I/O. */
    public List<Payment> claimDueBatch(Instant now, int limit, int maxAttempts, Instant claimUntil) {
        return jdbc.query("""
                        WITH due AS (
                          SELECT payment_id FROM payments
                          WHERE status IN ('CREATED', 'PROCESSING')
                            AND attempt_count < ?
                            AND next_attempt_at <= ?
                            AND (claim_until IS NULL OR claim_until < ?)
                          ORDER BY next_attempt_at, created_at
                          LIMIT ?
                          FOR UPDATE SKIP LOCKED
                        )
                        UPDATE payments p
                        SET status = 'PROCESSING', attempt_count = p.attempt_count + 1,
                            claim_until = ?, updated_at = ?
                        FROM due
                        WHERE p.payment_id = due.payment_id
                        RETURNING p.*
                        """, ROW_MAPPER,
                maxAttempts, utc(now), utc(now), limit, utc(claimUntil), utc(now));
    }

    public boolean resolve(PaymentId id, int expectedAttempt, PaymentStatus status) {
        int rows = jdbc.update("""
                        UPDATE payments SET status = ?, claim_until = NULL, updated_at = ?
                        WHERE payment_id = ? AND status = 'PROCESSING' AND attempt_count = ?
                        """, status.name(), OffsetDateTime.now(), id.value(), expectedAttempt);
        return rows == 1;
    }

    public boolean scheduleRetry(PaymentId id, int expectedAttempt, Instant nextAttemptAt) {
        int rows = jdbc.update("""
                        UPDATE payments
                        SET status = 'PROCESSING', claim_until = NULL, next_attempt_at = ?, updated_at = ?
                        WHERE payment_id = ? AND status = 'PROCESSING' AND attempt_count = ?
                        """, utc(nextAttemptAt), OffsetDateTime.now(), id.value(), expectedAttempt);
        return rows == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
