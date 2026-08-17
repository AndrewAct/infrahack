package io.infrahack.ridesharedispatch.repository;

import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.DispatchOffer;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.OfferId;
import io.infrahack.ridesharedispatch.domain.OfferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DispatchOfferRepository {

    private static final RowMapper<DispatchOffer> ROW_MAPPER = (rs, rowNum) -> new DispatchOffer(
            OfferId.of(rs.getObject("offer_id", UUID.class)),
            DispatchRequestId.of(rs.getObject("request_id", UUID.class)),
            AgentId.of(rs.getObject("agent_id", UUID.class)),
            OfferStatus.valueOf(rs.getString("status")),
            rs.getObject("reservation_token", UUID.class),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public DispatchOfferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DispatchOffer offer) {
        jdbc.update("""
                        INSERT INTO dispatch_offers
                            (offer_id, request_id, agent_id, status, reservation_token, expires_at,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                offer.id().value(), offer.requestId().value(), offer.agentId().value(),
                offer.status().name(), offer.reservationToken(),
                OffsetDateTime.ofInstant(offer.expiresAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(offer.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(offer.updatedAt(), ZoneOffset.UTC));
    }

    public Optional<DispatchOffer> findById(OfferId id) {
        return jdbc.query("SELECT * FROM dispatch_offers WHERE offer_id = ?", ROW_MAPPER, id.value())
                .stream().findFirst();
    }

    public Optional<DispatchOffer> findLatestByRequestId(DispatchRequestId requestId) {
        return jdbc.query("""
                        SELECT * FROM dispatch_offers WHERE request_id = ?
                        ORDER BY created_at DESC LIMIT 1
                        """,
                        ROW_MAPPER, requestId.value())
                .stream().findFirst();
    }

    /** Conditional update: only succeeds from PENDING. This is the CAS that stops a
     *  double-accept (or accept-after-reject) race from both succeeding. */
    public boolean transitionFromPending(OfferId id, OfferStatus newStatus) {
        int rows = jdbc.update("""
                        UPDATE dispatch_offers SET status = ?, updated_at = ?
                        WHERE offer_id = ? AND status = 'PENDING'
                        """,
                newStatus.name(), OffsetDateTime.now(), id.value());
        return rows == 1;
    }
}
