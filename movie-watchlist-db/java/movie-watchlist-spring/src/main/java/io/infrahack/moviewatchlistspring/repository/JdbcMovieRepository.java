package io.infrahack.moviewatchlistspring.repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.infrahack.moviewatchlistspring.model.Movie;
import io.infrahack.moviewatchlistspring.model.MovieId;

/** Postgres movie catalog via {@link JdbcTemplate}. Active under the {@code postgres} profile. */
@Repository
@Profile("postgres")
public class JdbcMovieRepository implements MovieRepository {

    private final JdbcTemplate jdbc;

    public JdbcMovieRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(MovieId id) {
        return Boolean.TRUE.equals(
                jdbc.query("SELECT 1 FROM movies WHERE id = ?", ResultSet::next, id.value()));
    }

    @Override
    public void save(Movie movie) {
        jdbc.update("INSERT INTO movies (id, title, release_year) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                movie.id().value(), movie.title(), movie.releaseYear());
    }

    @Override
    public List<Movie> findAll() {
        return jdbc.query("SELECT id, title, release_year FROM movies ORDER BY title",
                (rs, rowNum) -> new Movie(
                        new MovieId(rs.getObject("id", UUID.class)),
                        rs.getString("title"),
                        rs.getInt("release_year")));
    }
}
