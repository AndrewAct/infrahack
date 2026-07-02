package io.infrahack.moviewatchlistspring.repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import io.infrahack.moviewatchlistspring.model.Movie;
import io.infrahack.moviewatchlistspring.model.MovieId;

/** In-memory movie catalog (default bean). Seeded at startup by the data initializer. */
@Repository
@Profile("!postgres")
public class InMemoryMovieRepository implements MovieRepository {

    private final Map<MovieId, Movie> movies = new ConcurrentHashMap<>();

    @Override
    public boolean exists(MovieId id) {
        return movies.containsKey(id);
    }

    @Override
    public void save(Movie movie) {
        movies.put(movie.id(), movie);
    }

    @Override
    public List<Movie> findAll() {
        return List.copyOf(movies.values());
    }
}
