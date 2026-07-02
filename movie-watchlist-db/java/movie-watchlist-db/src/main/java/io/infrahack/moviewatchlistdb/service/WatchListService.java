package io.infrahack.moviewatchlistdb.service;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.infrahack.moviewatchlistdb.exception.DuplicateMovieException;
import io.infrahack.moviewatchlistdb.exception.MovieNotFoundException;
import io.infrahack.moviewatchlistdb.exception.MovieNotInListException;
import io.infrahack.moviewatchlistdb.exception.WatchListNotFoundException;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchListId;
import io.infrahack.moviewatchlistdb.repository.MovieRepository;
import io.infrahack.moviewatchlistdb.repository.WatchListRepository;

/**
 * Business rules for watch-list movie operations. This is the class the take-home is really about.
 *
 * <p>Every operation follows the same shape: <b>read -&gt; validate -&gt; mutate -&gt; await</b>.
 * It is written as an async composition so that:
 * <ul>
 *   <li>each validation runs <i>before</i> any mutation and short-circuits by throwing (the failed
 *       stage aborts and later stages never run — no "fell through and sent a second response" bug);</li>
 *   <li>the returned future only completes after the mutation stage resolves, so a caller that awaits
 *       it has necessarily awaited the persisted save (the "must await" pitfall, closed by design).</li>
 * </ul>
 * A thrown {@link io.infrahack.moviewatchlistdb.exception.DomainException} surfaces as the future's cause; the
 * web layer unwraps it and maps it to a status.
 */
public final class WatchListService {

    private final WatchListRepository watchListRepository;
    private final MovieRepository movieRepository;

    public WatchListService(WatchListRepository watchListRepository, MovieRepository movieRepository) {
        this.watchListRepository = Objects.requireNonNull(watchListRepository);
        this.movieRepository = Objects.requireNonNull(movieRepository);
    }

    /**
     * Add a movie to a watch list. Validation order (each maps to a distinct failure):
     * watch list exists (else 404) -&gt; movie exists in catalog (else 404) -&gt; not already in the list
     * (else 409). Only then does it insert and await.
     */
    public CompletableFuture<Void> addMovie(WatchListId watchListId, MovieId movieId) {
        return watchListRepository.exists(watchListId)
                // validate: watch list must exist
                .thenCompose(listExists -> {
                    if (!listExists) {
                        throw new WatchListNotFoundException(watchListId);
                    }
                    return movieRepository.exists(movieId); // read: is this a real movie?
                })
                // validate: movie must exist, then mutate (atomic add returns whether it was inserted)
                .thenCompose(movieExists -> {
                    if (!movieExists) {
                        throw new MovieNotFoundException(movieId);
                    }
                    return watchListRepository.addMovie(watchListId, movieId);
                })
                // validate: a false "inserted" flag means it was already in the list -> 409
                .thenAccept(inserted -> {
                    if (!inserted) {
                        throw new DuplicateMovieException(watchListId, movieId);
                    }
                });
    }

    /**
     * Remove a movie from a watch list. Shares the watch-list-exists check with add; unique to remove
     * is the "movie not in list" case, which is a 404 (no false success), not a silent 204.
     */
    public CompletableFuture<Void> removeMovie(WatchListId watchListId, MovieId movieId) {
        return watchListRepository.exists(watchListId)
                // validate: watch list must exist
                .thenCompose(listExists -> {
                    if (!listExists) {
                        throw new WatchListNotFoundException(watchListId);
                    }
                    return watchListRepository.removeMovie(watchListId, movieId); // mutate: targeted remove
                })
                // validate: nothing removed means the movie was not in the list -> 404
                .thenAccept(removed -> {
                    if (!removed) {
                        throw new MovieNotInListException(watchListId, movieId);
                    }
                });
    }

    /** Read the movie ids currently in a watch list (404 if the list is missing). */
    public CompletableFuture<Set<MovieId>> listMovies(WatchListId watchListId) {
        return watchListRepository.exists(watchListId)
                .thenCompose(listExists -> {
                    if (!listExists) {
                        throw new WatchListNotFoundException(watchListId);
                    }
                    return watchListRepository.movieEntries(watchListId);
                });
    }
}
