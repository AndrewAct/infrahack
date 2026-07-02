package io.infrahack.moviewatchlistspring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Spring Boot entry point.
 *
 * <p>We exclude {@link DataSourceAutoConfiguration} so the app boots with NO database by default
 * (the in-memory profile, used by tests and quick local runs). The Postgres {@code DataSource} is
 * defined explicitly and only activates under the {@code postgres} profile — mirroring the raw
 * module's "in-memory by default, Postgres when configured" behavior.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class MovieWatchlistSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieWatchlistSpringApplication.class, args);
    }
}
