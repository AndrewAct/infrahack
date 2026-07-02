package io.infrahack.moviewatchlistspring.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.infrahack.moviewatchlistspring.bootstrap.SampleData;
import io.infrahack.moviewatchlistspring.repository.MovieRepository;
import io.infrahack.moviewatchlistspring.repository.WatchListRepository;

/**
 * Seeds the in-memory store at startup (default profile only). Under the {@code postgres} profile the
 * DB is seeded by {@code db/seed.sql} instead, so this runner is disabled there.
 */
@Component
@Profile("!postgres")
public class DataInitializer implements CommandLineRunner {

    private final WatchListRepository watchLists;
    private final MovieRepository movies;

    public DataInitializer(WatchListRepository watchLists, MovieRepository movies) {
        this.watchLists = watchLists;
        this.movies = movies;
    }

    @Override
    public void run(String... args) {
        SampleData.seed(watchLists, movies);
    }
}
