package io.infrahack.explorejavaconcurrency.part02;

import io.infrahack.explorejavaconcurrency.kitchen.MealStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A single-meal experiment: decay calculation, container capacity, and indexes are external. */
public final class VersionedMeal {
    private final AtomicReference<MealSnapshot> state;
    private final Clock clock;

    public VersionedMeal(MealSnapshot initial, Clock clock) {
        this.state = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MealSnapshot snapshot() {
        return state.get();
    }

    public TransitionResult tryMove(MealSnapshot expected, String destination, Instant recalculatedExpiry) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(recalculatedExpiry, "recalculatedExpiry");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        MealSnapshot current = state.get();
        if (current != expected) {
            return TransitionResult.STALE;
        }
        if (current.status() != MealStatus.AVAILABLE) {
            return TransitionResult.TERMINAL;
        }
        Instant decisionAt = clock.instant();
        if (!decisionAt.isBefore(current.expiresAt()) || !decisionAt.isBefore(recalculatedExpiry)) {
            return TransitionResult.PAST_DEADLINE;
        }
        return state.compareAndSet(current, current.movedTo(destination, recalculatedExpiry))
                ? TransitionResult.MOVED : TransitionResult.STALE;
    }

    public TransitionResult expireIfVersion(long expectedVersion) {
        MealSnapshot current = state.get();
        if (current.version() != expectedVersion) {
            return TransitionResult.STALE;
        }
        if (current.status() != MealStatus.AVAILABLE) {
            return TransitionResult.TERMINAL;
        }
        if (clock.instant().isBefore(current.expiresAt())) {
            return TransitionResult.NOT_DUE;
        }
        return state.compareAndSet(current, current.expired())
                ? TransitionResult.EXPIRED : TransitionResult.STALE;
    }
}
