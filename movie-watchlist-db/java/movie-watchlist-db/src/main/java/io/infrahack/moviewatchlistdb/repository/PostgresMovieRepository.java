package io.infrahack.moviewatchlistdb.repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.MovieSearchCriteria;

/** Postgres-backed movie catalog. {@code exists} is an indexed primary-key lookup. */
public final class PostgresMovieRepository implements MovieRepository {

    private final DataSource dataSource;
    private final Executor executor;

    public PostgresMovieRepository(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Boolean> exists(MovieId id) {
        return async(() -> {
            String sql = "SELECT 1 FROM movies WHERE id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new RepositoryException("exists(movie) failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> save(Movie movie) {
        return async(() -> {
            String sql = "INSERT INTO movies (id, title, release_year, director, actors, genres) "
                    + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, movie.id().value());
                ps.setString(2, movie.title());
                ps.setInt(3, movie.releaseYear());
                ps.setString(4, movie.director());
                ps.setArray(5, c.createArrayOf("text", movie.actors().toArray(String[]::new)));
                ps.setArray(6, c.createArrayOf("text", movie.genres().toArray(String[]::new)));
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new RepositoryException("save(movie) failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Movie>> findAll() {
        return async(() -> {
            String sql = "SELECT id, title, release_year, director, actors, genres FROM movies ORDER BY title";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<Movie> catalog = new ArrayList<>();
                while (rs.next()) {
                    catalog.add(mapMovie(rs));
                }
                return catalog;
            } catch (SQLException e) {
                throw new RepositoryException("findAll(movies) failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Movie>> search(MovieSearchCriteria criteria) {
        return async(() -> {
            // This is very ugly. Need to refactor and never use similar method in prod.
            String sql = """
                    SELECT id, title, release_year, director, actors, genres
                    FROM movies
                    WHERE (? IS NULL OR release_year = ?)
                      AND (? IS NULL OR lower(director) LIKE '%' || lower(?) || '%')
                      AND (? IS NULL OR EXISTS (
                          SELECT 1 FROM unnest(actors) AS actor
                          WHERE lower(actor) LIKE '%' || lower(?) || '%'
                      ))
                      AND (? IS NULL OR genres @> ARRAY[?]::text[])
                    ORDER BY title
                    """;
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                Integer year = criteria.releaseYear().orElse(null);
                String director = criteria.director().orElse(null);
                String actor = criteria.actor().orElse(null);
                String genre = criteria.genre().orElse(null);

                ps.setObject(1, year);
                ps.setObject(2, year);
                ps.setString(3, director);
                ps.setString(4, director);
                ps.setString(5, actor);
                ps.setString(6, actor);
                ps.setString(7, genre);
                ps.setString(8, genre);

                try (ResultSet rs = ps.executeQuery()) {
                    List<Movie> catalog = new ArrayList<>();
                    while (rs.next()) {
                        catalog.add(mapMovie(rs));
                    }
                    return catalog;
                }
            } catch (SQLException e) {
                throw new RepositoryException("search(movies) failed", e);
            }
        });
    }

    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    private static Movie mapMovie(ResultSet rs) throws SQLException {
        return new Movie(
                new MovieId(rs.getObject("id", UUID.class)),
                rs.getString("title"),
                rs.getInt("release_year"),
                rs.getString("director"),
                readTextArray(rs.getArray("actors")),
                readTextArray(rs.getArray("genres")));
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        return Arrays.stream((Object[]) raw)
                .map(String::valueOf)
                .toList();
    }
}
