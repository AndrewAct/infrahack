package io.infrahack.explorejavaconcurrency.part01;

import io.infrahack.explorejavaconcurrency.kitchen.Meal;
import io.infrahack.explorejavaconcurrency.kitchen.MealStatus;
import io.infrahack.explorejavaconcurrency.kitchen.ExpiryResult;
import io.infrahack.explorejavaconcurrency.kitchen.PickupResult;
import io.infrahack.explorejavaconcurrency.kitchen.SynchronizedKitchen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PickupRaceTest {
    private static final Instant DEADLINE = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    @Timeout(5)
    void separateThreadSafeMapCallsCanClaimTwice() throws Exception {
        UnsafeKitchen unsafe = new UnsafeKitchen();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(unsafe::claim);
            Future<Boolean> second = workers.submit(unsafe::claim);
            assertTrue(first.get(3, TimeUnit.SECONDS));
            assertTrue(second.get(3, TimeUnit.SECONDS));
        }
        assertEquals(MealStatus.CLAIMED, unsafe.status());
    }

    @Test
    @Timeout(5)
    void twoRidersProduceExactlyOneSuccessfulClaim() throws Exception {
        SynchronizedKitchen kitchen = kitchenAt(DEADLINE.minusSeconds(1));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<PickupResult> first = workers.submit(() -> {
                start.await();
                return kitchen.claim("meal-1");
            });
            Future<PickupResult> second = workers.submit(() -> {
                start.await();
                return kitchen.claim("meal-1");
            });
            start.countDown();
            PickupResult a = first.get(3, TimeUnit.SECONDS);
            PickupResult b = second.get(3, TimeUnit.SECONDS);
            assertEquals(1, (a == PickupResult.CLAIMED ? 1 : 0) + (b == PickupResult.CLAIMED ? 1 : 0));
            assertEquals(1, (a == PickupResult.ALREADY_CLAIMED ? 1 : 0)
                    + (b == PickupResult.ALREADY_CLAIMED ? 1 : 0));
        }
        assertEquals(Optional.of(MealStatus.CLAIMED), kitchen.status("meal-1"));
    }

    @Test
    void claimRejectsAtExpiryEvenWithoutCleanup() {
        SynchronizedKitchen kitchen = kitchenAt(DEADLINE);
        assertEquals(PickupResult.EXPIRED, kitchen.claim("meal-1"));
        assertEquals(Optional.of(MealStatus.EXPIRED), kitchen.status("meal-1"));
        assertEquals(ExpiryResult.ALREADY_EXPIRED, kitchen.expire("meal-1"));
    }

    @Test
    void completedClaimCannotLaterBeExpired() {
        MutableClock clock = new MutableClock(DEADLINE.minusSeconds(1));
        SynchronizedKitchen kitchen = new SynchronizedKitchen(clock);
        kitchen.add(new Meal("meal-1", DEADLINE));
        assertEquals(PickupResult.CLAIMED, kitchen.claim("meal-1"));
        clock.set(DEADLINE);
        assertEquals(ExpiryResult.ALREADY_CLAIMED, kitchen.expire("meal-1"));
        assertEquals(Optional.of(MealStatus.CLAIMED), kitchen.status("meal-1"));
    }

    @Test
    void expiryFirstPreventsClaimAndDuplicateIdsAreRejected() {
        SynchronizedKitchen kitchen = kitchenAt(DEADLINE);
        assertEquals(ExpiryResult.EXPIRED, kitchen.expire("meal-1"));
        assertEquals(PickupResult.EXPIRED, kitchen.claim("meal-1"));
        assertThrows(IllegalArgumentException.class, () -> kitchen.add(new Meal("meal-1", DEADLINE)));
    }

    @Test
    @Timeout(5)
    void twoRidersAndCleanupAtDeadlineCannotClaimExpiredFood() throws Exception {
        SynchronizedKitchen kitchen = kitchenAt(DEADLINE);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(3)) {
            Future<PickupResult> first = workers.submit(() -> {
                start.await();
                return kitchen.claim("meal-1");
            });
            Future<PickupResult> second = workers.submit(() -> {
                start.await();
                return kitchen.claim("meal-1");
            });
            Future<ExpiryResult> cleaner = workers.submit(() -> {
                start.await();
                return kitchen.expire("meal-1");
            });
            start.countDown();
            assertEquals(PickupResult.EXPIRED, first.get(3, TimeUnit.SECONDS));
            assertEquals(PickupResult.EXPIRED, second.get(3, TimeUnit.SECONDS));
            ExpiryResult cleanup = cleaner.get(3, TimeUnit.SECONDS);
            assertTrue(cleanup == ExpiryResult.EXPIRED || cleanup == ExpiryResult.ALREADY_EXPIRED);
        }
        assertEquals(Optional.of(MealStatus.EXPIRED), kitchen.status("meal-1"));
    }

    private static SynchronizedKitchen kitchenAt(Instant now) {
        SynchronizedKitchen kitchen = new SynchronizedKitchen(Clock.fixed(now, ZoneOffset.UTC));
        kitchen.add(new Meal("meal-1", DEADLINE));
        return kitchen;
    }

    private static final class UnsafeKitchen {
        private final ConcurrentHashMap<String, MealStatus> state = new ConcurrentHashMap<>();
        private final CyclicBarrier bothReadAvailable = new CyclicBarrier(2);

        private UnsafeKitchen() {
            state.put("meal-1", MealStatus.AVAILABLE);
        }

        private boolean claim() throws Exception {
            if (state.get("meal-1") != MealStatus.AVAILABLE) {
                return false;
            }
            bothReadAvailable.await(3, TimeUnit.SECONDS);
            state.put("meal-1", MealStatus.CLAIMED);
            return true;
        }

        private MealStatus status() {
            return state.get("meal-1");
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        private void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock uses UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
