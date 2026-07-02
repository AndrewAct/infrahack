package io.infrahack.moviewatchlistspring.exception;

import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchListId;

/** Delete targeted a movie not in the (existing) list. Maps to 404 (no false success). */
public final class MovieNotInListException extends DomainException {

    public MovieNotInListException(WatchListId watchListId, MovieId movieId) {
        super("movie_not_in_list", "Movie " + movieId + " is not in watch list " + watchListId);
    }
}
