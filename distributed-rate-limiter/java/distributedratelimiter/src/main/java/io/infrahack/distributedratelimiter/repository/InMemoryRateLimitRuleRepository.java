package io.infrahack.distributedratelimiter.repository;

import java.util.List;
import java.util.Set;

import io.infrahack.distributedratelimiter.model.Dimension;
import io.infrahack.distributedratelimiter.model.FailurePolicy;
import io.infrahack.distributedratelimiter.model.RateLimitRule;

/**
 * Default, no-DB rule set for local development: a fixed snapshot of the same sample rules
 * committed in {@code db/seed.sql}, so the app has something to enforce even without Postgres.
 * Unlike Postgres mode, changing a limit here means a restart — only Postgres mode fulfills the
 * "update without redeploy" requirement (see {@code RuleCache}).
 */
public final class InMemoryRateLimitRuleRepository implements RateLimitRuleRepository {

    private static final List<RateLimitRule> RULES = List.of(
            // Tier-aware per-user steady rate + burst.
            new RateLimitRule(1, "user-free-rps", Set.of(Dimension.USER), "free",
                    5, 1, 10, null, 10, true),
            new RateLimitRule(2, "user-pro-rps", Set.of(Dimension.USER), "pro",
                    50, 1, 100, null, 10, true),
            new RateLimitRule(3, "user-enterprise-rps", Set.of(Dimension.USER), "enterprise",
                    500, 1, 1000, null, 10, true),
            // A second, independent window on the same USER dimension: per-second AND per-day.
            new RateLimitRule(4, "user-daily-quota", Set.of(Dimension.USER), null,
                    100_000, 86_400, 100_000, null, 20, true),
            // Protects a shared backend regardless of which user within a tenant calls it.
            new RateLimitRule(5, "tenant-endpoint-rps", Set.of(Dimension.TENANT, Dimension.ENDPOINT), null,
                    200, 1, 400, null, 30, true),
            // No userId on anonymous/unauthenticated traffic, so IP is the only usable dimension.
            new RateLimitRule(6, "ip-anonymous-rps", Set.of(Dimension.IP), null,
                    10, 1, 20, null, 5, true),
            new RateLimitRule(7, "api-key-rps", Set.of(Dimension.API_KEY), null,
                    100, 1, 200, null, 15, true),
            // Per-rule override: this one fails closed even though the platform default is fail-open.
            new RateLimitRule(8, "billing-endpoint-strict", Set.of(Dimension.TENANT, Dimension.ENDPOINT), null,
                    10, 1, 10, FailurePolicy.DENY, 100, true));

    @Override
    public List<RateLimitRule> findEnabledRules() {
        return RULES;
    }
}
