package io.infrahack.moviewatchlistdb.web;

import java.util.List;
import java.util.Optional;

import io.infrahack.moviewatchlistdb.model.GenreNormalizer;
import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieSearchCriteria;
import io.infrahack.moviewatchlistdb.service.MovieCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MovieCatalogController {

    private final MovieCatalogService movieCatalogService;

    public MovieCatalogController(MovieCatalogService movieCatalogService) {
        this.movieCatalogService = movieCatalogService;
    }

    @GetMapping("/movies")
    public List<MovieView> movies(@RequestParam Optional<String> actor,
                                  @RequestParam Optional<String> director,
                                  @RequestParam Optional<Integer> releaseYear,
                                  @RequestParam Optional<String> genre,
                                  @RequestParam Optional<String> category) {
        MovieSearchCriteria criteria = new MovieSearchCriteria(
                actor,
                director,
                releaseYear,
                genreOrCategory(genre, category));
        return movieCatalogService.searchMovies(criteria).join().stream()
                .map(MovieView::from)
                .toList();
    }

    private static Optional<String> genreOrCategory(Optional<String> genre, Optional<String> category) {
        if (genre.isPresent() && category.isPresent()
                && !GenreNormalizer.normalize(genre.get()).equals(GenreNormalizer.normalize(category.get()))) {
            throw new IllegalArgumentException("Use either 'genre' or 'category', not conflicting values");
        }
        return genre.isPresent() ? genre : category;
    }

    public record MovieView(String id,
                            String title,
                            int releaseYear,
                            String director,
                            List<String> actors,
                            List<String> genres) {
        static MovieView from(Movie movie) {
            return new MovieView(
                    movie.id().toString(),
                    movie.title(),
                    movie.releaseYear(),
                    movie.director(),
                    movie.actors(),
                    movie.genres());
        }
    }
}
