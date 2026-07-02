package io.infrahack.moviewatchlistdb.repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.MovieSearchCriteria;

/** In-memory movie catalog. Seeded at startup with a handful of test movies. */
public final class InMemoryMovieRepository implements MovieRepository {

    private final Map<MovieId, Movie> movies = new ConcurrentHashMap<>();
    private final Executor executor;

    public InMemoryMovieRepository(Executor executor) {
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Boolean> exists(MovieId id) {
        return async(() -> movies.containsKey(id));
    }

    @Override
    public CompletableFuture<Void> save(Movie movie) {
        return async(() -> {
            movies.put(movie.id(), movie);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<Movie>> findAll() {
        return async(() -> movies.values().stream()
                .sorted(Comparator.comparing(Movie::title))
                .toList());
    }

    @Override
    public CompletableFuture<List<Movie>> search(MovieSearchCriteria criteria) {
        return async(() -> movies.values().stream()
                .filter(movie -> criteria.releaseYear()
                        .map(year -> movie.releaseYear() == year)
                        .orElse(true))
                .filter(movie -> criteria.director()
                        .map(director -> containsIgnoreCase(movie.director(), director))
                        .orElse(true))
                .filter(movie -> criteria.actor()
                        .map(actor -> movie.actors().stream().anyMatch(a -> containsIgnoreCase(a, actor)))
                        .orElse(true))
                .filter(movie -> criteria.genre()
                        .map(genre -> movie.genres().contains(genre))
                        .orElse(true))
                .sorted(Comparator.comparing(Movie::title))
                .toList());
    }

    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    private static boolean containsIgnoreCase(String actual, String expected) {
        return actual.toLowerCase().contains(expected.toLowerCase());
    }
}
