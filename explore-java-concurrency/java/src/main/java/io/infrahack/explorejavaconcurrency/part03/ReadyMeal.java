package io.infrahack.explorejavaconcurrency.part03;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** One fixed meal/rider assignment; a successful claim is not a physical handoff. */
public final class ReadyMeal {
    public enum ClaimResult { CLAIMED, ALREADY_CLAIMED, WRONG_RIDER, EXPIRED }

    private enum State { PREPARING, READY, CLAIMED }

    private final String mealId;
    private final String assignedRider;
    private final long deadlineNanos;
    private final LongSupplier nanoClock;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private State state = State.PREPARING;

    public ReadyMeal(String mealId, String assignedRider, long deadlineNanos) {
        this(mealId, assignedRider, deadlineNanos, System::nanoTime);
    }

    ReadyMeal(String mealId, String assignedRider, long deadlineNanos, LongSupplier nanoClock) {
        this.mealId = Objects.requireNonNull(mealId);
        this.assignedRider = Objects.requireNonNull(assignedRider);
        this.deadlineNanos = deadlineNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public String mealId() {
        return mealId;
    }

    public boolean markReady() {
        synchronized (this) {
            if (state != State.PREPARING) {
                return false;
            }
            state = State.READY;
        }
        ready.complete(null);
        return true;
    }

    public ClaimResult awaitAndClaim(String riderId, Semaphore handoff)
            throws InterruptedException, ExecutionException {
        if (!assignedRider.equals(riderId)) {
            return ClaimResult.WRONG_RIDER;
        }
        long remaining = deadlineNanos - nanoClock.getAsLong();
        if (remaining <= 0) {
            return ClaimResult.EXPIRED;
        }
        try {
            ready.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return ClaimResult.EXPIRED;
        }

        remaining = deadlineNanos - nanoClock.getAsLong();
        if (remaining <= 0 || !handoff.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
            return ClaimResult.EXPIRED;
        }
        try {
            synchronized (this) {
                if (state == State.CLAIMED) {
                    return ClaimResult.ALREADY_CLAIMED;
                }
                if (nanoClock.getAsLong() >= deadlineNanos) {
                    return ClaimResult.EXPIRED;
                }
                state = State.CLAIMED;
                return ClaimResult.CLAIMED;
            }
        } finally {
            handoff.release();
        }
    }
}
