package io.infrahack.moviewatchlistdb.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class GenreNormalizer {

    private GenreNormalizer() {}

    public static String normalize(String raw) {
        String normalized = Objects.requireNonNull(raw, "genre")
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Genre cannot be blank");
        }
        return normalized;
    }

    public static List<String> normalizeAll(List<String> genres) {
        return Objects.requireNonNull(genres, "movie genres").stream()
                .map(GenreNormalizer::normalize)
                .distinct()
                .toList();
    }
}
