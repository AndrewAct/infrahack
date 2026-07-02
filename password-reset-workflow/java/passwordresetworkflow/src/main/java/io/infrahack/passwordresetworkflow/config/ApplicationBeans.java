package io.infrahack.passwordresetworkflow.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.infrahack.passwordresetworkflow.bootstrap.SampleData;
import io.infrahack.passwordresetworkflow.observability.Metrics;
import io.infrahack.passwordresetworkflow.repository.InMemoryPasswordResetRequestRepository;
import io.infrahack.passwordresetworkflow.repository.InMemoryUserRepository;
import io.infrahack.passwordresetworkflow.repository.PasswordResetRequestRepository;
import io.infrahack.passwordresetworkflow.repository.PostgresUserRepository;
import io.infrahack.passwordresetworkflow.repository.UserRepository;
import io.infrahack.passwordresetworkflow.service.PasswordResetService;
import io.infrahack.passwordresetworkflow.web.ObservabilityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationBeans {

    private static final Logger log = LoggerFactory.getLogger(ApplicationBeans.class);

    @Bean
    @ConditionalOnMissingBean
    AppConfig appConfig() {
        return AppConfig.load();
    }

    /** Injected everywhere time is read, so tests can pin and advance it deterministically. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    PersistenceResources persistenceResources(AppConfig config) {
        if (config.usePostgres()) {
            HikariDataSource dataSource = DataSourceFactory.create(config);
            log.info("Persistence: Postgres via configured DB_URL (pool max {})", config.dbPoolMax());
            log.info("Seed the DB with db/schema.sql + db/seed.sql if you have not already.");
            return PersistenceResources.postgres(dataSource, new PostgresUserRepository(dataSource));
        }

        InMemoryUserRepository users = new InMemoryUserRepository();
        SampleData.seed(users);
        log.info("Persistence: in-memory (default). Set DB_URL in .env to use Postgres.");
        return PersistenceResources.inMemory(users);
    }

    @Bean
    UserRepository userRepository(PersistenceResources resources) {
        return resources.userRepository();
    }

    /** Always in-process: codes live at most 30s, so durability would buy nothing (see interface). */
    @Bean
    PasswordResetRequestRepository resetRequestRepository() {
        return new InMemoryPasswordResetRequestRepository();
    }

    @Bean
    BooleanSupplier readinessProbe(PersistenceResources resources) {
        return resources.readinessProbe();
    }

    @Bean
    Metrics metrics() {
        return new Metrics();
    }

    @Bean
    ObservabilityFilter observabilityFilter(Metrics metrics) {
        return new ObservabilityFilter(metrics);
    }

    @Bean
    PasswordResetService passwordResetService(UserRepository users,
                                              PasswordResetRequestRepository requests,
                                              Clock clock,
                                              @Value("${password-reset.code-ttl-seconds}") long codeTtlSeconds,
                                              Metrics metrics) {
        return new PasswordResetService(users, requests, clock, codeTtlSeconds, metrics);
    }

    private static boolean pingDatabase(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public record PersistenceResources(HikariDataSource dataSource,
                                       UserRepository userRepository,
                                       BooleanSupplier readinessProbe) implements AutoCloseable {

        static PersistenceResources postgres(HikariDataSource dataSource, UserRepository users) {
            return new PersistenceResources(dataSource, users, () -> pingDatabase(dataSource));
        }

        static PersistenceResources inMemory(UserRepository users) {
            return new PersistenceResources(null, users, () -> true);
        }

        @Override
        public void close() {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
