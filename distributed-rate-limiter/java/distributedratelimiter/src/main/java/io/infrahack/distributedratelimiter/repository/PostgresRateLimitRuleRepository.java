package io.infrahack.distributedratelimiter.repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.infrahack.distributedratelimiter.model.Dimension;
import io.infrahack.distributedratelimiter.model.FailurePolicy;
import io.infrahack.distributedratelimiter.model.RateLimitRule;

/** Reads the control-plane rule set from Postgres; see db/schema.sql for the table. */
public final class PostgresRateLimitRuleRepository implements RateLimitRuleRepository {

    private static final String SELECT_ENABLED = """
            SELECT id, name, dimensions, tier, limit_amount, window_seconds, burst_capacity,
                   failure_policy, priority
            FROM rate_limit_rules
            WHERE enabled = true
            ORDER BY priority DESC, id
            """;

    private final DataSource dataSource;

    public PostgresRateLimitRuleRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<RateLimitRule> findEnabledRules() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ENABLED);
             ResultSet rs = ps.executeQuery()) {
            List<RateLimitRule> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(mapRow(rs));
            }
            return rules;
        } catch (SQLException e) {
            throw new RepositoryException("findEnabledRules() failed", e);
        }
    }

    private static RateLimitRule mapRow(ResultSet rs) throws SQLException {
        Set<Dimension> dimensions = readTextArray(rs.getArray("dimensions")).stream()
                .map(Dimension::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        String failurePolicyRaw = rs.getString("failure_policy");
        FailurePolicy failurePolicy = failurePolicyRaw == null ? null : FailurePolicy.valueOf(failurePolicyRaw);
        return new RateLimitRule(
                rs.getLong("id"),
                rs.getString("name"),
                dimensions,
                rs.getString("tier"),
                rs.getLong("limit_amount"),
                rs.getLong("window_seconds"),
                rs.getLong("burst_capacity"),
                failurePolicy,
                rs.getInt("priority"),
                true);
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        return Arrays.stream((Object[]) raw).map(String::valueOf).toList();
    }
}
