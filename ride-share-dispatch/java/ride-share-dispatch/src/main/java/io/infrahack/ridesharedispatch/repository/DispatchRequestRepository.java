package io.infrahack.ridesharedispatch.repository;

import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.DispatchRequestStatus;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent creation lives here, not in the service layer: the atomic guard is the
 * database unique constraint {@code uq_dispatch_requests_requester_key}, not an
 * application-level "SELECT then INSERT" (which would itself be a check-then-act race
 * between two concurrent retries of the same logical command).
 */
@Repository
public class DispatchRequestRepository {

    private static final RowMapper<DispatchRequest> ROW_MAPPER = (rs, rowNum) -> new DispatchRequest(
            DispatchRequestId.of(rs.getObject("request_id", UUID.class)),
            RequesterId.of(rs.getObject("requester_id", UUID.class)),
            rs.getString("idempotency_key"),
            rs.getString("request_fingerprint"),
            DispatchRequestStatus.valueOf(rs.getString("status")),
            rs.getString("service_type"),
            new GeoPoint(rs.getDouble("origin_lat"), rs.getDouble("origin_lng")),
            new GeoPoint(rs.getDouble("dest_lat"), rs.getDouble("dest_lng")),
            Optional.ofNullable(rs.getObject("matched_agent_id", UUID.class)).map(AgentId::of),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public DispatchRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if this call created the row (first attempt for this idempotency key);
     *         false if a row already existed (a retry -- the caller must then compare
     *         fingerprints, see DispatchRequestService).
     */
    public boolean insertIfAbsent(DispatchRequest request) {
        int rows = jdbc.update("""
                        INSERT INTO dispatch_requests
                            (request_id, requester_id, idempotency_key, request_fingerprint, status,
                             service_type, origin_lat, origin_lng, dest_lat, dest_lng, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (requester_id, idempotency_key) DO NOTHING
                        """,
                request.id().value(), request.requesterId().value(), request.idempotencyKey(),
                request.requestFingerprint(), request.status().name(), request.serviceType(),
                request.origin().latitude(), request.origin().longitude(),
                request.destination().latitude(), request.destination().longitude(),
                OffsetDateTime.ofInstant(request.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(request.updatedAt(), ZoneOffset.UTC));
        return rows == 1;
    }

    public Optional<DispatchRequest> findByRequesterAndKey(RequesterId requesterId, String idempotencyKey) {
        return jdbc.query("SELECT * FROM dispatch_requests WHERE requester_id = ? AND idempotency_key = ?",
                        ROW_MAPPER, requesterId.value(), idempotencyKey)
                .stream().findFirst();
    }

    public Optional<DispatchRequest> findById(DispatchRequestId id) {
        return jdbc.query("SELECT * FROM dispatch_requests WHERE request_id = ?", ROW_MAPPER, id.value())
                .stream().findFirst();
    }

    /** Conditional update: only succeeds from SEARCHING, so two concurrent offer-accepts
     *  for the same request cannot both win. */
    public boolean transitionFromSearching(DispatchRequestId id, DispatchRequestStatus newStatus,
                                            Optional<AgentId> matchedAgentId) {
        int rows = jdbc.update("""
                        UPDATE dispatch_requests
                        SET status = ?, matched_agent_id = ?, updated_at = ?
                        WHERE request_id = ? AND status = 'SEARCHING'
                        """,
                newStatus.name(), matchedAgentId.map(AgentId::value).orElse(null),
                OffsetDateTime.now(), id.value());
        return rows == 1;
    }

    /** Short DB lease so retries can recover matching without running two attempts for one request. */
    public boolean tryClaimMatching(DispatchRequestId id, UUID workerId, java.time.Instant claimUntil) {
        int rows = jdbc.update("""
                        UPDATE dispatch_requests
                        SET matching_claimed_by = ?, matching_claimed_until = ?
                        WHERE request_id = ? AND status = 'SEARCHING'
                          AND (matching_claimed_until IS NULL OR matching_claimed_until < now())
                        """,
                workerId, OffsetDateTime.ofInstant(claimUntil, ZoneOffset.UTC), id.value());
        return rows == 1;
    }

    public void releaseMatchingClaim(DispatchRequestId id, UUID workerId) {
        jdbc.update("""
                UPDATE dispatch_requests
                SET matching_claimed_by = NULL, matching_claimed_until = NULL
                WHERE request_id = ? AND matching_claimed_by = ?
                """, id.value(), workerId);
    }
}
