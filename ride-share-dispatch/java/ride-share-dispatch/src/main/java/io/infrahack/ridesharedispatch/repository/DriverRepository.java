package io.infrahack.ridesharedispatch.repository;

import io.infrahack.ridesharedispatch.domain.Driver;
import io.infrahack.ridesharedispatch.domain.DriverId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Durable driver profile only -- see Driver for why this table intentionally has no
 * lat/lng/status columns.
 */
@Repository
public class DriverRepository {

    private static final RowMapper<Driver> ROW_MAPPER = (rs, rowNum) -> new Driver(
            DriverId.of(rs.getObject("driver_id", java.util.UUID.class)),
            rs.getString("display_name"),
            rs.getString("service_type"),
            rs.getDouble("rating"),
            Driver.AccountStatus.valueOf(rs.getString("account_status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public DriverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Driver driver) {
        jdbc.update("""
                        INSERT INTO drivers (driver_id, display_name, service_type, rating, account_status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                driver.id().value(), driver.displayName(), driver.serviceType(), driver.rating(),
                driver.accountStatus().name(), OffsetDateTime.ofInstant(driver.createdAt(), ZoneOffset.UTC));
    }

    public Optional<Driver> findById(DriverId id) {
        return jdbc.query("SELECT * FROM drivers WHERE driver_id = ?", ROW_MAPPER, id.value())
                .stream().findFirst();
    }

    public boolean exists(DriverId id) {
        return Boolean.TRUE.equals(jdbc.query("SELECT 1 FROM drivers WHERE driver_id = ?",
                java.sql.ResultSet::next, id.value()));
    }
}
