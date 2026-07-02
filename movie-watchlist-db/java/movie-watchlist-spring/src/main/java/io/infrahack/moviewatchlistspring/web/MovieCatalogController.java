package io.infrahack.moviewatchlistspring.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.infrahack.moviewatchlistspring.model.Movie;
import io.infrahack.moviewatchlistspring.repository.MovieRepository;

/** {@code GET /movies} — lists the seeded catalog, so you have real movie ids to POST. */
@RestController
@RequestMapping("/movies")
public class MovieCatalogController {

    private final MovieRepository movieRepository;

    public MovieCatalogController(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    @GetMapping
    public List<MovieView> list() {
        return movieRepository.findAll().stream().map(MovieView::from).toList();
    }

    record MovieView(String id, String title, int releaseYear) {
        static MovieView from(Movie m) {
            return new MovieView(m.id().toString(), m.title(), m.releaseYear());
        }
    }
}
