package io.infrahack.moviewatchlistspring.repository;

import java.util.List;

import io.infrahack.moviewatchlistspring.model.Movie;
import io.infrahack.moviewatchlistspring.model.MovieId;

/** Persistence boundary for the movie catalog. */
public interface MovieRepository {

    boolean exists(MovieId id);

    void save(Movie movie);

    List<Movie> findAll();
}
