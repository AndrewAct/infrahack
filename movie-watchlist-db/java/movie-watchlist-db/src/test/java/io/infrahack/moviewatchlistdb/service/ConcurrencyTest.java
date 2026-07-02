package io.infrahack.moviewatchlistdb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.infrahack.moviewatchlistdb.exception.DuplicateMovieException;
import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchList;
import io.infrahack.moviewatchlistdb.model.WatchListId;
import io.infrahack.moviewatchlistdb.repository.InMemoryMovieRepository;
import io.infrahack.moviewatchlistdb.repository.InMemoryWatchListRepository;
import org.junit.jupiter.api.Test;

/**
 * The atomicity guarantee: many threads adding the SAME movie to the SAME list concurrently must
 * result in exactly one insert (201) and the rest as duplicates (409) — never two inserts, never a
 * lost update. This is what a naive read-check-then-write would get wrong; the concurrent-set add
 * (mirroring Postgres {@code ON CONFLICT}) gets it right.
 */
class ConcurrencyTest {

    @Test
    void concurrentAddsOfSameMovie_exactlyOneInserts() {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            InMemoryWatchListRepository watchLists = new InMemoryWatchListRepository(pool);
            InMemoryMovieRepository movies = new InMemoryMovieRepository(pool);
            WatchListService service = new WatchListService(watchLists, movies);

            WatchListId list = new WatchListId(UUID.randomUUID());
            watchLists.save(new WatchList(list, UUID.randomUUID(), "L")).join();
            MovieId movie = new MovieId(UUID.randomUUID());
            movies.save(new Movie(movie, "Contended", 2000)).join();

            int attempts = 50;
            AtomicInteger inserted = new AtomicInteger();
            AtomicInteger duplicates = new AtomicInteger();

            List<CompletableFuture<Void>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(service.addMovie(list, movie).handle((ok, ex) -> {
                    if (ex == null) {
                        inserted.incrementAndGet();
                    } else {
                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                        assertInstanceOf(DuplicateMovieException.class, cause);
                        duplicates.incrementAndGet();
                    }
                    return null;
                }));
            }
            CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).join();

            assertEquals(1, inserted.get(), "exactly one add should insert");
            assertEquals(attempts - 1, duplicates.get(), "all other adds should be duplicates");
            assertEquals(Set.of(movie), watchLists.movieEntries(list).join(), "exactly one movie stored");
        } finally {
            pool.shutdownNow();
        }
    }
}
