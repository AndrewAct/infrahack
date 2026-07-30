package io.infrahack.distributedratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import io.infrahack.distributedratelimiter.support.MutableClock;
import org.junit.jupiter.api.Test;

class InMemoryTokenBucketStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-01T12:00:00Z");

    @Test
    void singleBucketAllowsUpToCapacityThenRejects() {
        InMemoryTokenBucketStore store = new InMemoryTokenBucketStore(new MutableClock(T0));
        BucketRequest request = new BucketRequest("k1", 3, 1, 1);

        assertTrue(store.tryConsume(List.of(request)).get(0).allowed());
        assertTrue(store.tryConsume(List.of(request)).get(0).allowed());
        assertTrue(store.tryConsume(List.of(request)).get(0).allowed());
        assertFalse(store.tryConsume(List.of(request)).get(0).allowed(), "capacity is 3; the 4th immediate call must reject");
    }

    /**
     * The all-or-nothing guarantee: when one bucket in a multi-key check can't afford its cost,
     * NO bucket is charged - including ones with plenty of headroom. A naive per-rule
     * check-then-consume would leak a token out of "plenty" on every attempt that "scarce" blocks.
     */
    @Test
    void allOrNothing_rejectionOfOneBucketDoesNotConsumeAnother() {
        InMemoryTokenBucketStore store = new InMemoryTokenBucketStore(new MutableClock(T0));
        BucketRequest plenty = new BucketRequest("plenty", 100, 1, 1);
        BucketRequest scarce = new BucketRequest("scarce", 1, 1, 1);

        store.tryConsume(List.of(scarce)); // exhausts the capacity-1 bucket

        List<BucketResult> combined = store.tryConsume(List.of(plenty, scarce));
        assertFalse(combined.get(0).allowed(), "plenty must be reported as rejected too (all-or-nothing)");
        assertFalse(combined.get(1).allowed());

        List<BucketResult> plentyAlone = store.tryConsume(List.of(plenty));
        assertTrue(plentyAlone.get(0).allowed());
        assertEquals(99.0, plentyAlone.get(0).remaining(), 0.001,
                "plenty must still be at capacity-1: only this solo check should ever have spent a token");
    }

    @Test
    void refillsTokensAsClockAdvances() {
        MutableClock clock = new MutableClock(T0);
        InMemoryTokenBucketStore store = new InMemoryTokenBucketStore(clock);
        BucketRequest request = new BucketRequest("k1", 2, 2, 1); // capacity 2, refills 2/s

        store.tryConsume(List.of(request));
        store.tryConsume(List.of(request));
        assertFalse(store.tryConsume(List.of(request)).get(0).allowed());

        clock.advanceSeconds(1);
        assertTrue(store.tryConsume(List.of(request)).get(0).allowed(), "should have refilled after 1s");
    }
}
