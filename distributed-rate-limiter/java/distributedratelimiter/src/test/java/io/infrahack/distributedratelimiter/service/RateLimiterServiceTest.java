package io.infrahack.distributedratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.infrahack.distributedratelimiter.model.Dimension;
import io.infrahack.distributedratelimiter.model.FailurePolicy;
import io.infrahack.distributedratelimiter.model.RateLimitContext;
import io.infrahack.distributedratelimiter.model.RateLimitDecision;
import io.infrahack.distributedratelimiter.model.RateLimitRule;
import io.infrahack.distributedratelimiter.observability.Metrics;
import io.infrahack.distributedratelimiter.support.MutableClock;
import org.junit.jupiter.api.Test;

/** Rule matching, tier scoping, and multi-rule AND semantics - the parts above the bucket store. */
class RateLimiterServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-01T12:00:00Z");

    private RateLimiterService serviceWithRules(MutableClock clock, RateLimitRule... rules) {
        RuleCache ruleCache = new RuleCache(() -> List.of(rules));
        InMemoryTokenBucketStore store = new InMemoryTokenBucketStore(clock);
        return new RateLimiterService(ruleCache, store, FailurePolicy.ALLOW, new Metrics());
    }

    private static RateLimitRule rule(long id, String name, Set<Dimension> dims, String tier,
                                      long limit, long windowSeconds, long burst) {
        return new RateLimitRule(id, name, dims, tier, limit, windowSeconds, burst, null, 0, true);
    }

    @Test
    void allowsRequestsWithinSteadyRate() {
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "user-rps", Set.of(Dimension.USER), null, 5, 1, 5));
        RateLimitContext ctx = RateLimitContext.builder().userId("alice").endpoint("e").build();

        for (int i = 0; i < 5; i++) {
            assertTrue(service.check(ctx).allowed(), "request " + i + " should be allowed");
        }
    }

    @Test
    void rejectsOnceBurstCapacityIsExhausted() {
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "user-rps", Set.of(Dimension.USER), null, 5, 1, 5));
        RateLimitContext ctx = RateLimitContext.builder().userId("alice").endpoint("e").build();

        for (int i = 0; i < 5; i++) {
            service.check(ctx);
        }
        RateLimitDecision decision = service.check(ctx);
        assertFalse(decision.allowed());
        assertEquals("user-rps", decision.ruleName());
    }

    @Test
    void burstCapacityAboveSteadyRateToleratesShortSpikes() {
        // Steady rate is 2 req/s, but a burst capacity of 10 lets a caller spend 10 at once.
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "burst-rule", Set.of(Dimension.USER), null, 2, 1, 10));
        RateLimitContext ctx = RateLimitContext.builder().userId("bob").endpoint("e").build();

        for (int i = 0; i < 10; i++) {
            assertTrue(service.check(ctx).allowed(), "burst request " + i + " should be allowed");
        }
        assertFalse(service.check(ctx).allowed(), "11th immediate request should exceed the burst cap");
    }

    @Test
    void tierScopedRulesGiveDifferentLimitsPerTier() {
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "free-rps", Set.of(Dimension.USER), "free", 1, 1, 1),
                rule(2, "pro-rps", Set.of(Dimension.USER), "pro", 10, 1, 10));

        RateLimitContext freeCtx = RateLimitContext.builder().userId("dave").tier("free").endpoint("e").build();
        RateLimitContext proCtx = RateLimitContext.builder().userId("dave").tier("pro").endpoint("e").build();

        assertTrue(service.check(freeCtx).allowed());
        assertFalse(service.check(freeCtx).allowed(), "free tier should be capped at 1 rps");

        for (int i = 0; i < 10; i++) {
            assertTrue(service.check(proCtx).allowed(), "pro tier request " + i + " should be allowed");
        }
    }

    @Test
    void multipleMatchedRules_mostRestrictiveWinsUnderAndSemantics() {
        // A generous per-user rule, but a very tight shared tenant+endpoint rule.
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "user-rps", Set.of(Dimension.USER), null, 100, 1, 100),
                rule(2, "tenant-endpoint-rps", Set.of(Dimension.TENANT, Dimension.ENDPOINT), null, 1, 1, 1));

        RateLimitContext ctx = RateLimitContext.builder()
                .userId("erin").tenantId("acme").endpoint("shared").build();

        assertTrue(service.check(ctx).allowed());
        RateLimitDecision second = service.check(ctx);
        assertFalse(second.allowed(), "the tenant+endpoint bucket should block the second request");
        assertEquals("tenant-endpoint-rps", second.ruleName());
    }

    @Test
    void costGreaterThanOneConsumesProportionallyMoreTokens() {
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "user-rps", Set.of(Dimension.USER), null, 10, 1, 10));
        RateLimitContext expensive = RateLimitContext.builder().userId("grace").endpoint("e").cost(5).build();

        assertTrue(service.check(expensive).allowed(), "first cost-5 request fits in a 10-token bucket");
        assertTrue(service.check(expensive).allowed(), "second cost-5 request exactly exhausts the bucket");
        assertFalse(service.check(expensive).allowed(), "third cost-5 request should be rejected");
    }

    @Test
    void requestMissingARequiredDimensionSkipsThatRule() {
        RateLimiterService service = serviceWithRules(new MutableClock(T0),
                rule(1, "user-rps", Set.of(Dimension.USER), null, 1, 1, 1));

        // No userId on this context (e.g. anonymous traffic): the USER rule cannot apply.
        RateLimitContext anonymous = RateLimitContext.builder().ip("1.2.3.4").endpoint("e").build();
        assertTrue(service.check(anonymous).allowed(), "a rule needing a missing dimension must not block the request");
    }

    @Test
    void noMatchingRuleIsUnrestricted() {
        RateLimiterService service = serviceWithRules(new MutableClock(T0));
        RateLimitContext ctx = RateLimitContext.builder().userId("henry").endpoint("e").build();

        assertEquals(RateLimitDecision.unrestricted(), service.check(ctx));
    }
}
