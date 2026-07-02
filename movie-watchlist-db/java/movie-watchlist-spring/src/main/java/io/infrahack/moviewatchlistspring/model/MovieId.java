package io.infrahack.moviewatchlistspring.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Movie identifier as a value type. A {@code record} wrapping a {@link UUID} gives value-based
 * equals/hashCode (so duplicate detection compares by value, never by object reference) and makes it
 * a compile error to pass a {@link WatchListId} where a movie id is expected.
 */
public record MovieId(UUID value) {

    public MovieId {
        Objects.requireNonNull(value, "movie id value must not be null");
    }

    public static MovieId of(String raw) {
        return new MovieId(UUID.fromString(raw)); // throws IllegalArgumentException if malformed -> 400
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
