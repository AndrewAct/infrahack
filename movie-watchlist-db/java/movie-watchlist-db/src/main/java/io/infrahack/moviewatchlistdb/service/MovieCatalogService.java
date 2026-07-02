package io.infrahack.moviewatchlistdb.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieSearchCriteria;
import io.infrahack.moviewatchlistdb.repository.MovieRepository;

public final class MovieCatalogService {

    private final MovieRepository movieRepository;

    public MovieCatalogService(MovieRepository movieRepository) {
        this.movieRepository = Objects.requireNonNull(movieRepository);
    }

    public CompletableFuture<List<Movie>> searchMovies(MovieSearchCriteria criteria) {
        MovieSearchCriteria normalized = Objects.requireNonNull(criteria);
        return normalized.hasFilters() ? movieRepository.search(normalized) : movieRepository.findAll();
    }
}
