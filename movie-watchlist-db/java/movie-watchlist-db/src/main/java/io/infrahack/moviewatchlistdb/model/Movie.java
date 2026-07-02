package io.infrahack.moviewatchlistdb.model;

import java.util.List;
import java.util.Objects;

/**
 * A movie in our own small catalog (not IMDB). A movie must exist here before it can be added to a
 * watch list, which is what makes "add an unknown movie -> 404" a real, testable check.
 */
public record Movie(MovieId id,
                    String title,
                    int releaseYear,
                    String director,
                    List<String> actors,
                    List<String> genres) {

    public Movie {
        Objects.requireNonNull(id, "movie id");
        Objects.requireNonNull(title, "movie title");
        Objects.requireNonNull(director, "movie director");
        actors = List.copyOf(Objects.requireNonNull(actors, "movie actors"));
        genres = GenreNormalizer.normalizeAll(genres);
    }

    public Movie(MovieId id, String title, int releaseYear) {
        this(id, title, releaseYear, "", List.of(), List.of());
    }
}
