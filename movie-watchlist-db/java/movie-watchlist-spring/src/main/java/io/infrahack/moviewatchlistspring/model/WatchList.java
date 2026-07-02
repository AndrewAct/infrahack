package io.infrahack.moviewatchlistspring.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A watch list owned by a user. Note the movies are NOT here: membership lives in the repository
 * (a {@code watchlist_movies} join table), so add/remove stay O(index) instead of loading and
 * rewriting the whole list. Small DDD aggregate that references movies by id.
 */
public record WatchList(WatchListId id, UUID ownerId, String name) {

    public WatchList {
        Objects.requireNonNull(id, "watch list id");
        Objects.requireNonNull(name, "watch list name");
    }
}
