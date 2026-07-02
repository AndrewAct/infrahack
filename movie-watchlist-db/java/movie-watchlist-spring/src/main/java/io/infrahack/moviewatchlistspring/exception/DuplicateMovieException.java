package io.infrahack.moviewatchlistspring.exception;

import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchListId;

/** The movie is already in the watch list. Maps to 409 Conflict. */
public final class DuplicateMovieException extends DomainException {

    public DuplicateMovieException(WatchListId watchListId, MovieId movieId) {
        super("duplicate_movie", "Movie " + movieId + " is already in watch list " + watchListId);
    }
}
