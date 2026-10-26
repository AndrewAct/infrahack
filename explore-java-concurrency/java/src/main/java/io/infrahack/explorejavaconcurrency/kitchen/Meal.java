package io.infrahack.explorejavaconcurrency.kitchen;

import java.time.Instant;
import java.util.Objects;

public record Meal(String id, Instant expiresAt) {
    public Meal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
