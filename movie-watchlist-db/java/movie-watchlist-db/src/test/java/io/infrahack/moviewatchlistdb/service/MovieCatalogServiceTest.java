package io.infrahack.moviewatchlistdb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.MovieSearchCriteria;
import io.infrahack.moviewatchlistdb.repository.InMemoryMovieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovieCatalogServiceTest {

    private MovieCatalogService service;

    @BeforeEach
    void setUp() {
        InMemoryMovieRepository movies = new InMemoryMovieRepository(Runnable::run);
        movies.save(movie("Inception", 2010, "Christopher Nolan",
                List.of("Leonardo DiCaprio", "Joseph Gordon-Levitt"),
                List.of("sci-fi", "thriller"))).join();
        movies.save(movie("Interstellar", 2014, "Christopher Nolan",
                List.of("Matthew McConaughey", "Anne Hathaway"),
                List.of("sci-fi", "drama"))).join();
        movies.save(movie("Parasite", 2019, "Bong Joon Ho",
                List.of("Song Kang-ho", "Cho Yeo-jeong"),
                List.of("thriller", "drama", "comedy"))).join();
        service = new MovieCatalogService(movies);
    }

    @Test
    void searchByActorDirectorReleaseYearAndGenre_returnsOnlyMatchingMovies() {
        List<Movie> matches = service.searchMovies(new MovieSearchCriteria(
                Optional.of("anne"),
                Optional.of("nolan"),
                Optional.of(2014),
                Optional.of("Sci Fi"))).join();

        assertEquals(List.of("Interstellar"), matches.stream().map(Movie::title).toList());
    }

    @Test
    void searchByGenreNormalizesInput() {
        List<Movie> matches = service.searchMovies(new MovieSearchCriteria(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("SCI_FI"))).join();

        assertEquals(List.of("Inception", "Interstellar"), matches.stream().map(Movie::title).toList());
    }

    @Test
    void searchWithNoFilters_returnsFullCatalog() {
        List<Movie> matches = service.searchMovies(new MovieSearchCriteria(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())).join();

        assertEquals(3, matches.size());
    }

    private static Movie movie(String title,
                               int year,
                               String director,
                               List<String> actors,
                               List<String> genres) {
        return new Movie(new MovieId(UUID.randomUUID()), title, year, director, actors, genres);
    }
}
