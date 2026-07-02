package io.infrahack.moviewatchlistdb.model;

import java.util.Optional;

public record MovieSearchCriteria(Optional<String> actor,
                                  Optional<String> director,
                                  Optional<Integer> releaseYear,
                                  Optional<String> genre) {

    public MovieSearchCriteria {
        actor = normalizeText(actor);
        director = normalizeText(director);
        releaseYear = releaseYear == null ? Optional.empty() : releaseYear;
        genre = normalizeGenre(genre);
    }

    public boolean hasFilters() {
        return actor.isPresent() || director.isPresent() || releaseYear.isPresent() || genre.isPresent();
    }

    private static Optional<String> normalizeText(Optional<String> value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = value.get().strip();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static Optional<String> normalizeGenre(Optional<String> value) {
        return normalizeText(value).map(GenreNormalizer::normalize);
    }
}
