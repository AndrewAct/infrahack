package io.infrahack.moviewatchlistdb.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A watch-list identifier as a value type. Same rationale as {@link MovieId}: value equality and
 * a distinct type so a watch-list id can never be confused with a movie id at compile time.
 */
public record WatchListId(UUID value) {

    public WatchListId {
        Objects.requireNonNull(value, "watch list id value must not be null");
    }

    public static WatchListId of(String raw) {
        return new WatchListId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
