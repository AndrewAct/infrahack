package io.infrahack.ridesharedispatch.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wired by hand: as of Spring Boot 4.1 there is no bundled Flyway autoconfiguration
 * module on the classpath, so migration is a plain {@code flyway-core} call against the
 * autoconfigured {@link DataSource}. {@code initMethod = "migrate"} runs it once, during
 * this bean's construction -- i.e. before any other singleton bean can run a query,
 * since all singletons finish {@code preInstantiateSingletons()} before the application
 * context is considered ready.
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }
}
