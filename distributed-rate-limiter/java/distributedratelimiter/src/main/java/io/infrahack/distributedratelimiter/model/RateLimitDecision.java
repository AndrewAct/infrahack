package io.infrahack.distributedratelimiter.model;

/**
 * The outcome of a rate-limit check, already composed across every rule that matched the
 * request. {@code ruleName} names the binding (most restrictive) rule, so a caller can explain
 * *why* a request was rejected, or which limit is closest to being exhausted when it wasn't.
 */
public record RateLimitDecision(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterSeconds,
        String ruleName) {

    /** No rule matched this request at all: nothing to enforce. */
    public static RateLimitDecision unrestricted() {
        return new RateLimitDecision(true, Long.MAX_VALUE, Long.MAX_VALUE, 0, null);
    }
}
