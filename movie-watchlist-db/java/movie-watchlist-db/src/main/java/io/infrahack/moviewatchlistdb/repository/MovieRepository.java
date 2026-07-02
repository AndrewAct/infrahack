package io.infrahack.moviewatchlistdb.repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.MovieSearchCriteria;

/**
 * Persistence boundary for our small movie catalog. A movie must exist here before it can be added
 * to a watch list, which is what makes "add an unknown movie -> 404" a real check.
 */
public interface MovieRepository {

    /** Is this a known movie? An indexed primary-key lookup in Postgres; O(1) map lookup in memory. */
    CompletableFuture<Boolean> exists(MovieId id);

    /** Insert a catalog movie (used for seeding). */
    CompletableFuture<Void> save(Movie movie);

    /** List the catalog (small; used for a read endpoint and debugging). */
    CompletableFuture<List<Movie>> findAll();

    /** Search the catalog by optional actor, director, and release year filters. */
    CompletableFuture<List<Movie>> search(MovieSearchCriteria criteria);
}
