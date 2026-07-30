package io.infrahack.distributedratelimiter.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-JVM fallback for local development without Redis. All-or-nothing correctness across
 * matched buckets is enforced with one lock around the whole check - fine at dev traffic, but it
 * would not scale to the platform's target throughput. That scalability comes from Redis plus the
 * atomic Lua script, which needs no application-level lock at all; this class exists only so the
 * app runs with zero external dependencies out of the box.
 */
public final class InMemoryTokenBucketStore implements TokenBucketStore {

    private record BucketState(double tokens, long lastRefillMs) {}

    private final Map<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final Clock clock;

    public InMemoryTokenBucketStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<BucketResult> tryConsume(List<BucketRequest> requests) {
        synchronized (lock) {
            long now = clock.millis();
            double[] projectedTokens = new double[requests.size()];
            long[] retryAfterMs = new long[requests.size()];
            boolean allAllowed = true;

            for (int i = 0; i < requests.size(); i++) {
                BucketRequest r = requests.get(i);
                BucketState state = buckets.get(r.key());
                double tokens = state == null ? r.capacity() : state.tokens();
                long lastRefillMs = state == null ? now : state.lastRefillMs();

                double elapsedSec = Math.max(0, (now - lastRefillMs) / 1000.0);
                tokens = Math.min(r.capacity(), tokens + elapsedSec * r.refillPerSecond());
                projectedTokens[i] = tokens;

                if (tokens < r.cost()) {
                    double deficit = r.cost() - tokens;
                    retryAfterMs[i] = Math.round((deficit / r.refillPerSecond()) * 1000);
                    allAllowed = false;
                }
            }

            List<BucketResult> results = new ArrayList<>(requests.size());
            for (int i = 0; i < requests.size(); i++) {
                BucketRequest r = requests.get(i);
                if (allAllowed) {
                    double remaining = projectedTokens[i] - r.cost();
                    buckets.put(r.key(), new BucketState(remaining, now));
                    results.add(new BucketResult(true, remaining, 0));
                } else {
                    results.add(new BucketResult(false, projectedTokens[i], retryAfterMs[i]));
                }
            }
            return results;
        }
    }
}
