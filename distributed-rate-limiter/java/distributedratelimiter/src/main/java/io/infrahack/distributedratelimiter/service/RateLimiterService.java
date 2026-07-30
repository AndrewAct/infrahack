package io.infrahack.distributedratelimiter.service;

import java.util.List;
import java.util.stream.Collectors;

import io.infrahack.distributedratelimiter.model.FailurePolicy;
import io.infrahack.distributedratelimiter.model.RateLimitContext;
import io.infrahack.distributedratelimiter.model.RateLimitDecision;
import io.infrahack.distributedratelimiter.model.RateLimitRule;
import io.infrahack.distributedratelimiter.observability.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The check-and-consume engine: resolves which rules apply to a request, builds one bucket key
 * per matched rule, and asks the {@link TokenBucketStore} to consume all of them atomically. A
 * request is allowed only if every matched rule allows it (AND semantics) - e.g. a request can be
 * within its own per-user rate but still rejected for exceeding its tenant's shared endpoint
 * budget.
 */
public final class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final RuleCache ruleCache;
    private final TokenBucketStore store;
    private final FailurePolicy defaultFailurePolicy;
    private final Metrics metrics;

    public RateLimiterService(RuleCache ruleCache, TokenBucketStore store,
                              FailurePolicy defaultFailurePolicy, Metrics metrics) {
        this.ruleCache = ruleCache;
        this.store = store;
        this.defaultFailurePolicy = defaultFailurePolicy;
        this.metrics = metrics;
    }

    public RateLimitDecision check(RateLimitContext context) {
        List<RateLimitRule> matched = ruleCache.rules().stream()
                .filter(rule -> matches(rule, context))
                .toList();

        if (matched.isEmpty()) {
            metrics.countDecision("none", "allowed");
            return RateLimitDecision.unrestricted();
        }

        List<BucketRequest> bucketRequests = matched.stream()
                .map(rule -> toBucketRequest(rule, context))
                .toList();

        List<BucketResult> results;
        try {
            results = store.tryConsume(bucketRequests);
        } catch (StoreUnavailableException e) {
            metrics.countStoreError();
            return onStoreUnavailable(matched, e);
        }

        return compose(matched, results);
    }

    /** A rule applies only when its tier filter matches and every dimension it needs is present. */
    private boolean matches(RateLimitRule rule, RateLimitContext context) {
        return rule.appliesToTier(context.tier())
                && rule.dimensions().stream().allMatch(d -> context.valueFor(d) != null);
    }

    private BucketRequest toBucketRequest(RateLimitRule rule, RateLimitContext context) {
        return new BucketRequest(bucketKey(rule, context), rule.burstCapacity(),
                rule.refillPerSecond(), context.cost());
    }

    private String bucketKey(RateLimitRule rule, RateLimitContext context) {
        String dimensionValues = rule.dimensions().stream()
                .sorted() // enum natural order: a stable key regardless of Set iteration order
                .map(context::valueFor)
                .collect(Collectors.joining(":"));
        return "rl:%d:%s".formatted(rule.id(), dimensionValues);
    }

    private RateLimitDecision compose(List<RateLimitRule> matched, List<BucketResult> results) {
        boolean allowed = results.stream().allMatch(BucketResult::allowed);

        int bindingIndex = 0;
        if (allowed) {
            // Report whichever matched rule has the least headroom left, for response headers.
            double bestRatio = Double.MAX_VALUE;
            for (int i = 0; i < matched.size(); i++) {
                double ratio = results.get(i).remaining() / (double) matched.get(i).burstCapacity();
                if (ratio < bestRatio) {
                    bestRatio = ratio;
                    bindingIndex = i;
                }
            }
        } else {
            // Report whichever blocking rule demands the longest wait.
            long worstRetryMs = -1;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).retryAfterMs() > worstRetryMs) {
                    worstRetryMs = results.get(i).retryAfterMs();
                    bindingIndex = i;
                }
            }
        }

        RateLimitRule bindingRule = matched.get(bindingIndex);
        BucketResult binding = results.get(bindingIndex);
        long retryAfterSeconds = allowed ? 0 : (binding.retryAfterMs() + 999) / 1000;

        metrics.countDecision(bindingRule.name(), allowed ? "allowed" : "rejected");
        return new RateLimitDecision(
                allowed,
                bindingRule.burstCapacity(),
                Math.max(0, (long) binding.remaining()),
                retryAfterSeconds,
                bindingRule.name());
    }

    /** Fail-open unless the default or any matched rule is explicitly DENY (most restrictive wins). */
    private RateLimitDecision onStoreUnavailable(List<RateLimitRule> matched, StoreUnavailableException e) {
        log.warn("Token bucket store unavailable, applying failure policy", e);
        return matched.stream()
                .filter(rule -> effectivePolicy(rule) == FailurePolicy.DENY)
                .findFirst()
                .map(rule -> new RateLimitDecision(false, rule.burstCapacity(), 0, rule.windowSeconds(), rule.name()))
                .orElseGet(RateLimitDecision::unrestricted);
    }

    private FailurePolicy effectivePolicy(RateLimitRule rule) {
        return rule.failurePolicy() != null ? rule.failurePolicy() : defaultFailurePolicy;
    }
}
