package io.infrahack.moviewatchlistspring.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Postgres wiring, active only under the {@code postgres} profile (we excluded Spring Boot's
 * DataSource auto-config so the app boots with no DB by default).
 *
 * <p>The pool max is the backpressure bound. {@code prepareThreshold=0} disables pgJDBC's server-side
 * prepared statements — required to run through the Supabase <b>transaction pooler</b>, where a
 * prepared statement created on one backend may not exist on the next.
 */
@Configuration
@Profile("postgres")
public class PersistenceConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(
            @Value("${DB_URL}") String url,
            @Value("${DB_USER:postgres}") String username,
            @Value("${DB_PASSWORD}") String password,
            @Value("${DB_POOL_MAX:10}") int poolMax) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolMax);
        config.setPoolName("watchlist-pool");
        config.setConnectionTimeout(5_000);
        config.addDataSourceProperty("prepareThreshold", "0"); // transaction pooler compatibility
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
