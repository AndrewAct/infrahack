package io.infrahack.explorejavaconcurrency.part02;

import io.infrahack.explorejavaconcurrency.kitchen.MealStatus;

import java.time.Instant;
import java.util.Objects;

public record MealSnapshot(
        String id, String container, Instant expiresAt, MealStatus status, long version) {
    public MealSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        if (id.isBlank() || container.isBlank() || version < 0) {
            throw new IllegalArgumentException("invalid meal snapshot");
        }
    }

    MealSnapshot movedTo(String destination, Instant recalculatedExpiry) {
        return new MealSnapshot(id, destination, recalculatedExpiry, status, version + 1);
    }

    MealSnapshot expired() {
        return new MealSnapshot(id, container, expiresAt, MealStatus.EXPIRED, version + 1);
    }
}
