package io.infrahack.moviewatchlistdb.repository;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchList;
import io.infrahack.moviewatchlistdb.model.WatchListId;

/**
 * Persistence boundary for watch lists and the movies they contain.
 *
 * <p>Async on purpose: real persistence (JDBC to Supabase) is blocking I/O we offload to a bounded
 * pool, and returning {@link CompletableFuture} forces callers to <i>await</i> the save before
 * responding. Two implementations sit behind this: {@code InMemory...} (default, tests) and
 * {@code Postgres...} (Supabase). The service layer never knows which one it has.
 */
public interface WatchListRepository {

    /** Does the watch list exist? Drives the 404 for a missing list. */
    CompletableFuture<Boolean> exists(WatchListId id);

    /** Create/replace a watch list. Used to seed a sample list; watch-list CRUD is otherwise out of scope. */
    CompletableFuture<Void> save(WatchList watchList);

    /**
     * Atomically add a movie to the list.
     *
     * @return {@code true} if this call inserted the movie, {@code false} if it was already in the list.
     *         The duplicate check is part of this one atomic op (concurrent set / {@code ON CONFLICT}),
     *         so concurrent adds of the same movie yield exactly one {@code true}.
     * @implSpec precondition: the caller (service) has already verified the watch list exists.
     */
    CompletableFuture<Boolean> addMovie(WatchListId watchListId, MovieId movieId);

    /**
     * Remove exactly one movie from the list.
     *
     * @return {@code true} if the movie was present and removed, {@code false} if it was not there.
     */
    CompletableFuture<Boolean> removeMovie(WatchListId watchListId, MovieId movieId);

    /** Snapshot of the movie ids currently in the list (used by tests and a read endpoint). */
    CompletableFuture<Set<MovieId>> movieEntries(WatchListId watchListId);
}
