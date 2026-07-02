package io.infrahack.moviewatchlistdb.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Builds the HikariCP pool from {@link AppConfig}. */
public final class DataSourceFactory {

    private DataSourceFactory() {}

    public static HikariDataSource create(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.dbUrl().orElseThrow(
                () -> new IllegalStateException("DB_URL is required for Postgres mode")));
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());

        // The pool cap is the backpressure bound: at most this many concurrent DB operations. Extra
        // requests wait for a connection (up to connectionTimeout) instead of overwhelming Postgres.
        hikari.setMaximumPoolSize(config.dbPoolMax());
        hikari.setPoolName("watchlist-pool");

        // Fail fast rather than hang: if no connection is free within 5s, the borrow throws and the
        // request becomes a clean 500 — a saturation signal you can see, not a silent stall.
        hikari.setConnectionTimeout(5_000);

        return new HikariDataSource(hikari);
    }
}
