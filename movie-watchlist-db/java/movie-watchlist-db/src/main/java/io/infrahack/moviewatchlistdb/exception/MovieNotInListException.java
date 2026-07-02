package io.infrahack.moviewatchlistdb.exception;

import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchListId;

/**
 * Delete was asked to remove a movie that is not in the (existing) watch list. Maps to 404.
 *
 * <p>This is the "no false success" rule: a removal that changed nothing must not report 204. We
 * distinguish it from {@link WatchListNotFoundException} so the two 404s carry different {@code code}s.
 */
public final class MovieNotInListException extends DomainException {

    public MovieNotInListException(WatchListId watchListId, MovieId movieId) {
        super("movie_not_in_list", "Movie " + movieId + " is not in watch list " + watchListId);
    }
}
