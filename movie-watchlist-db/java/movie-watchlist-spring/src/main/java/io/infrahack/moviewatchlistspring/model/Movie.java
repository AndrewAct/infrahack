package io.infrahack.moviewatchlistspring.model;

import java.util.Objects;

/** A movie in our own catalog. Must exist before it can be added to a watch list. */
public record Movie(MovieId id, String title, int releaseYear) {

    public Movie {
        Objects.requireNonNull(id, "movie id");
        Objects.requireNonNull(title, "movie title");
    }
}
