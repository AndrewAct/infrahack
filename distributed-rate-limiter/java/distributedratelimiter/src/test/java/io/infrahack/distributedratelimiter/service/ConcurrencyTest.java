package io.infrahack.distributedratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.infrahack.distributedratelimiter.support.MutableClock;
import org.junit.jupiter.api.Test;

/**
 * The atomicity guarantee under concurrency: many threads hammering the SAME bucket key must
 * never let more than {@code capacity} requests through. This is what a naive read-check-then-
 * write (without the store's lock) would get wrong: two threads could both read "1 token left"
 * and both consume it, letting the bucket go negative. In production this guarantee comes from
 * Redis's single-threaded Lua execution instead of a JVM lock (see RedisTokenBucketStore).
 */
class ConcurrencyTest {

    @Test
    void concurrentChecksOnSameBucket_neverExceedCapacity() throws InterruptedException {
        InMemoryTokenBucketStore store = new InMemoryTokenBucketStore(
                new MutableClock(Instant.parse("2026-07-01T12:00:00Z")));
        BucketRequest request = new BucketRequest("shared-key", 20, 0, 1); // fixed budget, no refill

        int threads = 16;
        int attemptsPerThread = 10; // 160 attempts total against a budget of 20
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (store.tryConsume(List.of(request)).get(0).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(20, allowed.get(), "exactly the bucket's capacity should be allowed, no more and no less");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
