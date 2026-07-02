package io.infrahack.moviewatchlistdb.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A watch list owned by a user.
 *
 * <p>Note what is <i>not</i> here: the collection of movies. That collection lives in the
 * repository (a {@code watchlist_movies} join table in Postgres, a concurrent set in memory), not in
 * this object. Storing tens of thousands of movie ids inside the aggregate would force us to load and
 * rewrite the whole list on every add/remove — the exact scan we are trying to avoid at scale.
 */
public record WatchList(WatchListId id, UUID ownerId, String name) {

    public WatchList {
        Objects.requireNonNull(id, "watch list id");
        Objects.requireNonNull(name, "watch list name");
    }
}
