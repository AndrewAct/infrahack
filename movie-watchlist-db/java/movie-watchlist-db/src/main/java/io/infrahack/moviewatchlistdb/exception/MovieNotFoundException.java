package io.infrahack.moviewatchlistdb.exception;

import io.infrahack.moviewatchlistdb.model.MovieId;

/** The movie being added is not in our catalog. Maps to 404. */
public final class MovieNotFoundException extends DomainException {

    public MovieNotFoundException(MovieId id) {
        super("movie_not_found", "Movie not found in catalog: " + id);
    }
}
