package io.infrahack.moviewatchlistdb.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.infrahack.moviewatchlistdb.bootstrap.SampleData;
import io.infrahack.moviewatchlistdb.observability.Metrics;
import io.infrahack.moviewatchlistdb.repository.InMemoryMovieRepository;
import io.infrahack.moviewatchlistdb.repository.InMemoryWatchListRepository;
import io.infrahack.moviewatchlistdb.repository.MovieRepository;
import io.infrahack.moviewatchlistdb.repository.PostgresMovieRepository;
import io.infrahack.moviewatchlistdb.repository.PostgresWatchListRepository;
import io.infrahack.moviewatchlistdb.repository.WatchListRepository;
import io.infrahack.moviewatchlistdb.service.MovieCatalogService;
import io.infrahack.moviewatchlistdb.service.WatchListService;
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

    @Bean(destroyMethod = "shutdown")
    ExecutorService persistenceExecutor(AppConfig config) {
        return Executors.newFixedThreadPool(config.dbPoolMax(), namedThreads("persistence"));
    }

    @Bean(destroyMethod = "close")
    PersistenceResources persistenceResources(AppConfig config, ExecutorService persistenceExecutor) {
        if (config.usePostgres()) {
            HikariDataSource dataSource = DataSourceFactory.create(config);
            log.info("Persistence: Postgres via configured DB_URL (pool max {})", config.dbPoolMax());
            log.info("Seed the DB with db/schema.sql + db/seed.sql if you have not already.");
            return PersistenceResources.postgres(
                    dataSource,
                    new PostgresWatchListRepository(dataSource, persistenceExecutor),
                    new PostgresMovieRepository(dataSource, persistenceExecutor));
        }

        InMemoryWatchListRepository watchLists = new InMemoryWatchListRepository(persistenceExecutor);
        InMemoryMovieRepository movies = new InMemoryMovieRepository(persistenceExecutor);
        SampleData.seed(watchLists, movies);
        log.info("Persistence: in-memory (default). Set DB_URL in .env to use Postgres.");
        return PersistenceResources.inMemory(watchLists, movies);
    }

    @Bean
    WatchListRepository watchListRepository(PersistenceResources resources) {
        return resources.watchListRepository();
    }

    @Bean
    MovieRepository movieRepository(PersistenceResources resources) {
        return resources.movieRepository();
    }

    @Bean
    BooleanSupplier readinessProbe(PersistenceResources resources) {
        return resources.readinessProbe();
    }

    @Bean
    WatchListService watchListService(WatchListRepository watchLists, MovieRepository movies) {
        return new WatchListService(watchLists, movies);
    }

    @Bean
    MovieCatalogService movieCatalogService(MovieRepository movies) {
        return new MovieCatalogService(movies);
    }

    @Bean
    Metrics metrics() {
        return new Metrics();
    }

    private static boolean pingDatabase(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public record PersistenceResources(HikariDataSource dataSource,
                                       WatchListRepository watchListRepository,
                                       MovieRepository movieRepository,
                                       BooleanSupplier readinessProbe) implements AutoCloseable {

        static PersistenceResources postgres(HikariDataSource dataSource,
                                             WatchListRepository watchLists,
                                             MovieRepository movies) {
            return new PersistenceResources(dataSource, watchLists, movies, () -> pingDatabase(dataSource));
        }

        static PersistenceResources inMemory(WatchListRepository watchLists, MovieRepository movies) {
            return new PersistenceResources(null, watchLists, movies, () -> true);
        }

        @Override
        public void close() {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
