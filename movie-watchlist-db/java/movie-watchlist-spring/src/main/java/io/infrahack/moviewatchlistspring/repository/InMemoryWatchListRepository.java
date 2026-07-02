package io.infrahack.moviewatchlistspring.repository;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchList;
import io.infrahack.moviewatchlistspring.model.WatchListId;

/**
 * In-memory repository — the default bean (active when the {@code postgres} profile is NOT set), used
 * by tests and quick local runs. {@code addMovie} relies on a concurrent set's atomic {@code add}:
 * two threads adding the same movie can't both insert (mirrors Postgres {@code ON CONFLICT}).
 */
@Repository
@Profile("!postgres")
public class InMemoryWatchListRepository implements WatchListRepository {

    private final Map<WatchListId, WatchList> watchLists = new ConcurrentHashMap<>();
    private final Map<WatchListId, Set<MovieId>> moviesByList = new ConcurrentHashMap<>();

    @Override
    public boolean exists(WatchListId id) {
        return watchLists.containsKey(id);
    }

    @Override
    public void save(WatchList watchList) {
        watchLists.put(watchList.id(), watchList);
    }

    @Override
    public boolean addMovie(WatchListId watchListId, MovieId movieId) {
        return moviesByList.computeIfAbsent(watchListId, k -> ConcurrentHashMap.newKeySet()).add(movieId);
    }

    @Override
    public boolean removeMovie(WatchListId watchListId, MovieId movieId) {
        Set<MovieId> movies = moviesByList.get(watchListId);
        return movies != null && movies.remove(movieId);
    }

    @Override
    public Set<MovieId> movieEntries(WatchListId watchListId) {
        Set<MovieId> movies = moviesByList.get(watchListId);
        return movies == null ? Set.of() : Set.copyOf(movies);
    }
}
