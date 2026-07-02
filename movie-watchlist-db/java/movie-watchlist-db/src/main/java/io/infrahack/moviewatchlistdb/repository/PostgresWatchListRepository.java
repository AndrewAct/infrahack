package io.infrahack.moviewatchlistdb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchList;
import io.infrahack.moviewatchlistdb.model.WatchListId;

/**
 * Postgres-backed {@link WatchListRepository}. Movies for a list live in the {@code watchlist_movies} join table
 * with a composite primary key {@code (watchlist_id, movie_id)}, which is what makes this scale:
 * <ul>
 *   <li><b>Add is atomic:</b> {@code INSERT ... ON CONFLICT DO NOTHING} — the PK rejects duplicates,
 *       so two concurrent adds of the same movie can't both succeed. Affected rows (1 vs 0) tells us
 *       inserted vs duplicate without a separate SELECT (no read-modify-write race).</li>
 *   <li><b>Dedup and remove are O(index):</b> both hit the PK/index directly, never scanning the
 *       list — so a watch list with tens of thousands of movies costs the same as a tiny one.</li>
 * </ul>
 * Blocking JDBC runs on the injected (bounded) executor so it never ties up the HTTP threads.
 */
public final class PostgresWatchListRepository implements WatchListRepository {

    private final DataSource dataSource;
    private final Executor executor;

    public PostgresWatchListRepository(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Boolean> exists(WatchListId id) {
        return async(() -> {
            String sql = "SELECT 1 FROM watchlists WHERE id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new RepositoryException("exists(watchlist) failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> save(WatchList watchList) {
        return async(() -> {
            String sql = "INSERT INTO watchlists (id, owner_id, name) VALUES (?, ?, ?) "
                    + "ON CONFLICT (id) DO UPDATE SET owner_id = EXCLUDED.owner_id, name = EXCLUDED.name";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, watchList.id().value());
                ps.setObject(2, watchList.ownerId());
                ps.setString(3, watchList.name());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new RepositoryException("save(watchlist) failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> addMovie(WatchListId watchListId, MovieId movieId) {
        return async(() -> {
            String sql = "INSERT INTO watchlist_movies (watchlist_id, movie_id) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, watchListId.value());
                ps.setObject(2, movieId.value());
                return ps.executeUpdate() == 1; // 1 = inserted, 0 = already in the list
            } catch (SQLException e) {
                throw new RepositoryException("addMovie failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removeMovie(WatchListId watchListId, MovieId movieId) {
        return async(() -> {
            String sql = "DELETE FROM watchlist_movies WHERE watchlist_id = ? AND movie_id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, watchListId.value());
                ps.setObject(2, movieId.value());
                return ps.executeUpdate() == 1; // 1 = removed, 0 = wasn't there
            } catch (SQLException e) {
                throw new RepositoryException("removeMovie failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Set<MovieId>> movieEntries(WatchListId watchListId) {
        return async(() -> {
            String sql = "SELECT movie_id FROM watchlist_movies WHERE watchlist_id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, watchListId.value());
                try (ResultSet rs = ps.executeQuery()) {
                    Set<MovieId> movies = new HashSet<>();
                    while (rs.next()) {
                        movies.add(new MovieId(rs.getObject("movie_id", UUID.class)));
                    }
                    return movies;
                }
            } catch (SQLException e) {
                throw new RepositoryException("movieEntries failed", e);
            }
        });
    }

    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }
}
