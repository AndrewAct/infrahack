package io.infrahack.moviewatchlistdb.repository;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchList;
import io.infrahack.moviewatchlistdb.model.WatchListId;

/**
 * In-memory {@link WatchListRepository}: the default, and what the unit tests run against (no network).
 *
 * <p>Each list's movies are held in a {@code ConcurrentHashMap<WatchListId, Set<MovieId>>} whose value is a
 * concurrent key-set. That structure is what makes add/remove atomic:
 * <ul>
 *   <li><b>The race we are preventing:</b> a naive add would read the set, check {@code contains},
 *       then {@code add}. Two threads adding the same movie could both read "absent" and both think
 *       they inserted — a lost update, and a double 201. </li>
 *   <li><b>The fix:</b> {@code set.add(id)} on a concurrent set is a single atomic
 *       compare-and-set that returns whether <i>this</i> call inserted. Exactly one concurrent add
 *       returns {@code true}; the rest get {@code false} and become 409s.</li>
 *   <li><b>Simpler alternative:</b> if the whole app were single-threaded we could use a plain
 *       {@code HashSet} with no concurrency control. We use the concurrent set because add is the
 *       contended path and the HTTP server is multi-threaded — the same reason Postgres uses
 *       {@code ON CONFLICT} rather than SELECT-then-INSERT.</li>
 * </ul>
 */
public final class InMemoryWatchListRepository implements WatchListRepository {

    private final Map<WatchListId, WatchList> watchLists = new ConcurrentHashMap<>();
    private final Map<WatchListId, Set<MovieId>> moviesByList = new ConcurrentHashMap<>();
    private final Executor executor;

    public InMemoryWatchListRepository(Executor executor) {
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Boolean> exists(WatchListId id) {
        return async(() -> watchLists.containsKey(id));
    }

    @Override
    public CompletableFuture<Void> save(WatchList watchList) {
        // Persist the entity only. Like the SQL model (a row in `watchlists`), a new list simply has
        // no movie rows yet; the movie set is created lazily on the first addMovie.
        return async(() -> {
            watchLists.put(watchList.id(), watchList);
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> addMovie(WatchListId watchListId, MovieId movieId) {
        // computeIfAbsent is the single, atomic place the movie set is born (lazily, on first add);
        // set.add is the atomic insert-or-reject that returns whether THIS call inserted.
        return async(() -> moviesByList
                .computeIfAbsent(watchListId, k -> ConcurrentHashMap.newKeySet())
                .add(movieId));
    }

    @Override
    public CompletableFuture<Boolean> removeMovie(WatchListId watchListId, MovieId movieId) {
        return async(() -> {
            Set<MovieId> movies = moviesByList.get(watchListId);
            // set.remove targets exactly this id and returns whether it was present.
            return movies != null && movies.remove(movieId);
        });
    }

    @Override
    public CompletableFuture<Set<MovieId>> movieEntries(WatchListId watchListId) {
        return async(() -> {
            Set<MovieId> movies = moviesByList.get(watchListId);
            return movies == null ? Set.of() : Set.copyOf(movies); // immutable snapshot
        });
    }

    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }
}
