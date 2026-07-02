package io.infrahack.passwordresetworkflow.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import io.infrahack.passwordresetworkflow.model.User;

/**
 * Postgres-backed {@link UserRepository} over the Supabase transaction pooler.
 *
 * <p>Calls are synchronous: the HikariCP pool cap plus its 5s connection timeout already bound
 * how much concurrent DB work can pile up, so an async executor layer would add moving parts
 * without adding safety at this scale.
 */
public final class PostgresUserRepository implements UserRepository {

    private final DataSource dataSource;

    public PostgresUserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String sql = "SELECT email, password_hash FROM password_reset_users WHERE email = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new User(rs.getString("email"), rs.getString("password_hash")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RepositoryException("findByEmail failed", e);
        }
    }

    @Override
    public void save(User user) {
        String sql = "INSERT INTO password_reset_users (email, password_hash) VALUES (?, ?) "
                + "ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash, updated_at = now()";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.email());
            ps.setString(2, user.passwordHash());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("save(user) failed", e);
        }
    }

    @Override
    public boolean updatePassword(String email, String newPasswordHash) {
        String sql = "UPDATE password_reset_users SET password_hash = ?, updated_at = now() WHERE email = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newPasswordHash);
            ps.setString(2, email);
            // Affected-row count is the persistence evidence: 0 means no such user, no silent no-op.
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RepositoryException("updatePassword failed", e);
        }
    }
}
