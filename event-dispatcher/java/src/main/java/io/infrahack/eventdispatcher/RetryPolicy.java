package io.infrahack.eventdispatcher;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class RetryPolicy {
    private final int maxAttempts;
    private final List<Duration> retryDelays;

    public RetryPolicy(int maxAttempts, List<Duration> retryDelays) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Objects.requireNonNull(retryDelays, "retryDelays");
        if (retryDelays.size() != maxAttempts - 1) {
            throw new IllegalArgumentException("retryDelays must contain maxAttempts - 1 entries");
        }
        for (Duration retryDelay : retryDelays) {
            if (retryDelay.isNegative()) {
                throw new IllegalArgumentException("retry delay cannot be negative");
            }
        }
        this.maxAttempts = maxAttempts;
        this.retryDelays = List.copyOf(retryDelays);
    }

    public static RetryPolicy noRetries() {
        return new RetryPolicy(1, List.of());
    }

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, List.of(Duration.ofMillis(100), Duration.ofMillis(500)));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean shouldRetry(int completedAttempt) {
        return completedAttempt < maxAttempts;
    }

    public Duration delayAfter(int completedAttempt) {
        if (!shouldRetry(completedAttempt)) {
            throw new IllegalArgumentException("no retry delay after final attempt");
        }
        return retryDelays.get(completedAttempt - 1);
    }
}
