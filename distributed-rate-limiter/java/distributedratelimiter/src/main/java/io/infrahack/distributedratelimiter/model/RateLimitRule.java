package io.infrahack.distributedratelimiter.model;

import java.util.Set;

/**
 * One dynamically configurable limit: which {@link Dimension}s compose its bucket key, which
 * subscription tier it applies to ({@code tier == null} means all tiers), and its token-bucket
 * shape. Rules live in Postgres and are refreshed into every instance's {@code RuleCache} on a
 * timer, so edits here take effect without redeploying gateways (see RuleCache).
 *
 * <p>Multiple rules can share the same dimensions with different {@code windowSeconds} (e.g. 100
 * req/s and 10,000 req/day both scoped to USER) — that is how multiple simultaneous windows are
 * expressed, rather than a single rule needing a list of windows.
 */
public record RateLimitRule(
        long id,
        String name,
        Set<Dimension> dimensions,
        String tier,
        long limitAmount,
        long windowSeconds,
        long burstCapacity,
        FailurePolicy failurePolicy,
        int priority,
        boolean enabled) {

    public RateLimitRule {
        if (dimensions == null || dimensions.isEmpty()) {
            throw new IllegalArgumentException("dimensions must not be empty");
        }
        dimensions = Set.copyOf(dimensions);
        if (limitAmount <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("limitAmount and windowSeconds must be positive");
        }
        if (burstCapacity < limitAmount) {
            throw new IllegalArgumentException("burstCapacity must be >= limitAmount (the steady-state rate)");
        }
    }

    /** Steady-state token refill rate implied by limitAmount/windowSeconds. */
    public double refillPerSecond() {
        return (double) limitAmount / windowSeconds;
    }

    public boolean appliesToTier(String requestTier) {
        return tier == null || tier.equalsIgnoreCase(requestTier);
    }
}
