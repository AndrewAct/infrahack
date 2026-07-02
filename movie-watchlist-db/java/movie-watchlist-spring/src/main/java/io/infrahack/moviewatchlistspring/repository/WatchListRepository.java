package io.infrahack.moviewatchlistspring.repository;

import java.util.Set;

import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchList;
import io.infrahack.moviewatchlistspring.model.WatchListId;

/**
 * Persistence boundary for watch lists and the movies they contain. Two beans implement it, selected
 * by Spring profile: in-memory (default/tests) and JdbcTemplate (Postgres/Supabase pooler).
 */
public interface WatchListRepository {

    boolean exists(WatchListId id);

    void save(WatchList watchList);

    /**
     * Atomically add a movie. {@code true} = inserted, {@code false} = already present. The duplicate
     * check is part of this one atomic op ({@code INSERT ... ON CONFLICT} / concurrent set), so
     * concurrent adds of the same movie yield exactly one {@code true}.
     */
    boolean addMovie(WatchListId watchListId, MovieId movieId);

    /** Remove exactly one movie. {@code true} = removed, {@code false} = was not present. */
    boolean removeMovie(WatchListId watchListId, MovieId movieId);

    Set<MovieId> movieEntries(WatchListId watchListId);
}
