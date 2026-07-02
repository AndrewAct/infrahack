package io.infrahack.moviewatchlistspring.repository;

import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchList;
import io.infrahack.moviewatchlistspring.model.WatchListId;

/**
 * Postgres repository via {@link JdbcTemplate} — raw SQL, no ORM. Active under the {@code postgres}
 * profile. The atomic {@code INSERT ... ON CONFLICT DO NOTHING} (affected rows 1 vs 0) is what makes
 * add safe under concurrency and O(index), and it works through the Supabase transaction pooler
 * (which is why we run with {@code prepareThreshold=0}).
 */
@Repository
@Profile("postgres")
public class JdbcWatchListRepository implements WatchListRepository {

    private final JdbcTemplate jdbc;

    public JdbcWatchListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(WatchListId id) {
        return Boolean.TRUE.equals(
                jdbc.query("SELECT 1 FROM watchlists WHERE id = ?", ResultSet::next, id.value()));
    }

    @Override
    public void save(WatchList watchList) {
        jdbc.update("INSERT INTO watchlists (id, owner_id, name) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET owner_id = EXCLUDED.owner_id, name = EXCLUDED.name",
                watchList.id().value(), watchList.ownerId(), watchList.name());
    }

    @Override
    public boolean addMovie(WatchListId watchListId, MovieId movieId) {
        int rows = jdbc.update("INSERT INTO watchlist_movies (watchlist_id, movie_id) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", watchListId.value(), movieId.value());
        return rows == 1;
    }

    @Override
    public boolean removeMovie(WatchListId watchListId, MovieId movieId) {
        int rows = jdbc.update("DELETE FROM watchlist_movies WHERE watchlist_id = ? AND movie_id = ?",
                watchListId.value(), movieId.value());
        return rows == 1;
    }

    @Override
    public Set<MovieId> movieEntries(WatchListId watchListId) {
        return jdbc.query("SELECT movie_id FROM watchlist_movies WHERE watchlist_id = ?",
                        (rs, rowNum) -> new MovieId(rs.getObject("movie_id", UUID.class)),
                        watchListId.value())
                .stream().collect(Collectors.toSet());
    }
}
