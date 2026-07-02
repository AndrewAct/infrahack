package io.infrahack.moviewatchlistspring.model;

import java.util.Objects;
import java.util.UUID;

/** Watch-list identifier as a value type. Same rationale as {@link MovieId}. */
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
