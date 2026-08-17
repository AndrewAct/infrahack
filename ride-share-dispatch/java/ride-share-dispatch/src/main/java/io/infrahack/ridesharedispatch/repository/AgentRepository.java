package io.infrahack.ridesharedispatch.repository;

import io.infrahack.ridesharedispatch.domain.Agent;
import io.infrahack.ridesharedispatch.domain.AgentId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Durable agent profile only -- see Agent for why this table intentionally has no
 * lat/lng/status columns.
 */
@Repository
public class AgentRepository {

    private static final RowMapper<Agent> ROW_MAPPER = (rs, rowNum) -> new Agent(
            AgentId.of(rs.getObject("agent_id", java.util.UUID.class)),
            rs.getString("display_name"),
            rs.getString("service_type"),
            rs.getDouble("rating"),
            Agent.AccountStatus.valueOf(rs.getString("account_status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Agent agent) {
        jdbc.update("""
                        INSERT INTO agents (agent_id, display_name, service_type, rating, account_status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                agent.id().value(), agent.displayName(), agent.serviceType(), agent.rating(),
                agent.accountStatus().name(), OffsetDateTime.ofInstant(agent.createdAt(), ZoneOffset.UTC));
    }

    public Optional<Agent> findById(AgentId id) {
        return jdbc.query("SELECT * FROM agents WHERE agent_id = ?", ROW_MAPPER, id.value())
                .stream().findFirst();
    }

    public boolean exists(AgentId id) {
        return Boolean.TRUE.equals(jdbc.query("SELECT 1 FROM agents WHERE agent_id = ?",
                java.sql.ResultSet::next, id.value()));
    }
}
