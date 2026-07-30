package io.infrahack.distributedratelimiter.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.infrahack.distributedratelimiter.observability.Metrics;
import io.infrahack.distributedratelimiter.repository.InMemoryRateLimitRuleRepository;
import io.infrahack.distributedratelimiter.repository.PostgresRateLimitRuleRepository;
import io.infrahack.distributedratelimiter.repository.RateLimitRuleRepository;
import io.infrahack.distributedratelimiter.service.InMemoryTokenBucketStore;
import io.infrahack.distributedratelimiter.service.RateLimiterService;
import io.infrahack.distributedratelimiter.service.RedisTokenBucketStore;
import io.infrahack.distributedratelimiter.service.RuleCache;
import io.infrahack.distributedratelimiter.service.TokenBucketStore;
import io.infrahack.distributedratelimiter.web.ObservabilityFilter;
import io.infrahack.distributedratelimiter.web.RateLimitInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Bean
    Metrics metrics() {
        return new Metrics();
    }

    @Bean(destroyMethod = "close")
    PersistenceResources persistenceResources(AppConfig config) {
        if (config.usePostgres()) {
            HikariDataSource dataSource = DataSourceFactory.create(config);
            log.info("Rules: Postgres via configured DB_URL (pool max {})", config.dbPoolMax());
            log.info("Seed the DB with db/schema.sql + db/seed.sql if you have not already.");
            return PersistenceResources.postgres(dataSource, new PostgresRateLimitRuleRepository(dataSource));
        }

        log.info("Rules: in-memory sample set (default). Set DB_URL in .env to use Postgres.");
        return PersistenceResources.inMemory(new InMemoryRateLimitRuleRepository());
    }

    @Bean
    RateLimitRuleRepository ruleRepository(PersistenceResources resources) {
        return resources.ruleRepository();
    }

    @Bean
    BooleanSupplier readinessProbe(PersistenceResources resources) {
        return resources.readinessProbe();
    }

    /** Loads once at startup, then refreshes on a timer; see RuleCache for the stale-on-failure contract. */
    @Bean
    RuleCache ruleCache(RateLimitRuleRepository repository) {
        return new RuleCache(repository);
    }

    /**
     * Redis (distributed, for multiple gateway/backend instances) when REDIS_URL is set, else an
     * in-process fallback so the app runs with zero external dependencies out of the box - see
     * each implementation's Javadoc for the trade-off this default makes.
     */
    @Bean
    TokenBucketStore tokenBucketStore(AppConfig config, Clock clock) {
        if (config.useRedis()) {
            log.info("Token buckets: Redis via configured REDIS_URL");
            return new RedisTokenBucketStore(RedisClientFactory.create(config));
        }
        log.info("Token buckets: in-memory (default, single JVM only). Set REDIS_URL in .env for distributed mode.");
        return new InMemoryTokenBucketStore(clock);
    }

    @Bean
    RateLimiterService rateLimiterService(RuleCache ruleCache, TokenBucketStore store,
                                          AppConfig config, Metrics metrics) {
        return new RateLimiterService(ruleCache, store, config.defaultFailurePolicy(), metrics);
    }

    @Bean
    ObservabilityFilter observabilityFilter(Metrics metrics) {
        return new ObservabilityFilter(metrics);
    }

    @Bean
    RateLimitInterceptor rateLimitInterceptor(RateLimiterService rateLimiterService) {
        return new RateLimitInterceptor(rateLimiterService);
    }

    private static boolean pingDatabase(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public record PersistenceResources(HikariDataSource dataSource,
                                       RateLimitRuleRepository ruleRepository,
                                       BooleanSupplier readinessProbe) implements AutoCloseable {

        static PersistenceResources postgres(HikariDataSource dataSource, RateLimitRuleRepository repository) {
            return new PersistenceResources(dataSource, repository, () -> pingDatabase(dataSource));
        }

        static PersistenceResources inMemory(RateLimitRuleRepository repository) {
            return new PersistenceResources(null, repository, () -> true);
        }

        @Override
        public void close() {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
