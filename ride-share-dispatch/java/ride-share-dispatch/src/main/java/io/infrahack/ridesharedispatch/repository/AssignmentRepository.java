package io.infrahack.ridesharedispatch.repository;

import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.domain.AssignmentStatus;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.OfferId;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link #transitionVersion} is the module's canonical example of optimistic
 * concurrency control: {@code UPDATE ... WHERE id = ? AND version = ?}. rowsAffected
 * == 1 means this caller's read was still current and its write landed; rowsAffected
 * == 0 means someone else committed a transition first, and the caller reloads and
 * decides whether to retry or surface a conflict. No row lock is held while the
 * caller does other work (e.g. calling the payment provider), unlike {@code SELECT
 * ... FOR UPDATE}. See docs/DESIGN.md "OCC vs SELECT FOR UPDATE".
 */
@Repository
public class AssignmentRepository {

    private static final RowMapper<Assignment> ROW_MAPPER = (rs, rowNum) -> new Assignment(
            AssignmentId.of(rs.getObject("assignment_id", UUID.class)),
            DispatchRequestId.of(rs.getObject("request_id", UUID.class)),
            OfferId.of(rs.getObject("offer_id", UUID.class)),
            RequesterId.of(rs.getObject("requester_id", UUID.class)),
            AgentId.of(rs.getObject("agent_id", UUID.class)),
            AssignmentStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            Optional.ofNullable(rs.getObject("started_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
            Optional.ofNullable(rs.getObject("completed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant));

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Assignment assignment) {
        jdbc.update("""
                        INSERT INTO assignments
                            (assignment_id, request_id, offer_id, requester_id, agent_id, status, version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                assignment.id().value(), assignment.requestId().value(), assignment.offerId().value(),
                assignment.requesterId().value(), assignment.agentId().value(), assignment.status().name(),
                assignment.version(), OffsetDateTime.ofInstant(assignment.createdAt(), ZoneOffset.UTC));
    }

    public Optional<Assignment> findById(AssignmentId id) {
        return jdbc.query("SELECT * FROM assignments WHERE assignment_id = ?", ROW_MAPPER, id.value())
                .stream().findFirst();
    }

    public Optional<Assignment> findByOfferId(OfferId offerId) {
        return jdbc.query("SELECT * FROM assignments WHERE offer_id = ?", ROW_MAPPER, offerId.value())
                .stream().findFirst();
    }

    /** @return true if {@code expectedVersion} was still current and the transition committed. */
    public boolean transitionVersion(AssignmentId id, AssignmentStatus newStatus, long expectedVersion) {
        String timestampColumn = switch (newStatus) {
            case IN_PROGRESS -> "started_at";
            case COMPLETED -> "completed_at";
            default -> null;
        };
        String sql = timestampColumn == null
                ? "UPDATE assignments SET status = ?, version = version + 1 WHERE assignment_id = ? AND version = ?"
                : "UPDATE assignments SET status = ?, version = version + 1, " + timestampColumn
                        + " = ? WHERE assignment_id = ? AND version = ?";

        int rows = timestampColumn == null
                ? jdbc.update(sql, newStatus.name(), id.value(), expectedVersion)
                : jdbc.update(sql, newStatus.name(), OffsetDateTime.now(), id.value(), expectedVersion);
        return rows == 1;
    }
}
