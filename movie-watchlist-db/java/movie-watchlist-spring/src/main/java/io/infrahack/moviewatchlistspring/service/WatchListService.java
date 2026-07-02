package io.infrahack.moviewatchlistspring.service;

import java.util.Set;

import org.springframework.stereotype.Service;

import io.infrahack.moviewatchlistspring.exception.DuplicateMovieException;
import io.infrahack.moviewatchlistspring.exception.MovieNotFoundException;
import io.infrahack.moviewatchlistspring.exception.MovieNotInListException;
import io.infrahack.moviewatchlistspring.exception.WatchListNotFoundException;
import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchListId;
import io.infrahack.moviewatchlistspring.repository.MovieRepository;
import io.infrahack.moviewatchlistspring.repository.WatchListRepository;

/**
 * Business rules for watch-list movie operations. Same pipeline as the raw module — <b>read → validate
 * → mutate</b> — but synchronous, because Spring MVC serves each request on its own thread. Each
 * validation throws to short-circuit; the {@code @RestControllerAdvice} maps the exception to a status.
 */
@Service
public class WatchListService {

    private final WatchListRepository watchListRepository;
    private final MovieRepository movieRepository;

    public WatchListService(WatchListRepository watchListRepository, MovieRepository movieRepository) {
        this.watchListRepository = watchListRepository;
        this.movieRepository = movieRepository;
    }

    /** watch list exists (else 404) -> movie exists (else 404) -> not duplicate (else 409) -> insert. */
    public void addMovie(WatchListId watchListId, MovieId movieId) {
        if (!watchListRepository.exists(watchListId)) {
            throw new WatchListNotFoundException(watchListId);
        }
        if (!movieRepository.exists(movieId)) {
            throw new MovieNotFoundException(movieId);
        }
        if (!watchListRepository.addMovie(watchListId, movieId)) {
            throw new DuplicateMovieException(watchListId, movieId);
        }
    }

    /** watch list exists (else 404) -> movie was present (else 404, no false success). */
    public void removeMovie(WatchListId watchListId, MovieId movieId) {
        if (!watchListRepository.exists(watchListId)) {
            throw new WatchListNotFoundException(watchListId);
        }
        if (!watchListRepository.removeMovie(watchListId, movieId)) {
            throw new MovieNotInListException(watchListId, movieId);
        }
    }

    public Set<MovieId> listMovies(WatchListId watchListId) {
        if (!watchListRepository.exists(watchListId)) {
            throw new WatchListNotFoundException(watchListId);
        }
        return watchListRepository.movieEntries(watchListId);
    }
}
