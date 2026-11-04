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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    void secondRiderBlocksOnMonitorAndExactlyOneClaims() throws Exception {
        GateClock clock = new GateClock(DEADLINE.minusSeconds(1));
        SynchronizedKitchen kitchen = kitchenWith(clock, "meal-1");
        try {
            FutureTask<PickupResult> owner = claimInThread(kitchen, "meal-1", "rider-a");
            clock.awaitOwnerInsideMonitor();
            FutureTask<PickupResult> contender = new FutureTask<>(() -> kitchen.claim("meal-1", "rider-b"));
            Thread contenderThread = new Thread(contender);
            contenderThread.start();
            awaitBlocked(contenderThread);
            clock.release();
            assertEquals(PickupResult.CLAIMED, owner.get(3, TimeUnit.SECONDS));
            assertEquals(PickupResult.ALREADY_CLAIMED, contender.get(3, TimeUnit.SECONDS));
        } finally {
            clock.release();
        }
        assertEquals(Optional.of(MealStatus.CLAIMED), kitchen.status("meal-1"));
    }

    @Test
    @Timeout(5)
    void perMealCasCanFillOneBagTwice() throws Exception {
        PerMealCasKitchen cas = new PerMealCasKitchen("meal-1", "meal-2");
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(() -> cas.claim("meal-1", "rider-a"));
            Future<Boolean> second = workers.submit(() -> cas.claim("meal-2", "rider-a"));
            assertTrue(first.get(3, TimeUnit.SECONDS));
            assertTrue(second.get(3, TimeUnit.SECONDS));
        }
        assertEquals(2, cas.claimedMeals());
    }

    @Test
    @Timeout(5)
    void monitorKeepsSecondMealOutOfTheSameBag() throws Exception {
        GateClock clock = new GateClock(DEADLINE.minusSeconds(1));
        SynchronizedKitchen kitchen = kitchenWith(clock, "meal-1", "meal-2");
        try {
            FutureTask<PickupResult> owner = claimInThread(kitchen, "meal-1", "rider-a");
            clock.awaitOwnerInsideMonitor();
            FutureTask<PickupResult> contender = new FutureTask<>(() -> kitchen.claim("meal-2", "rider-a"));
            Thread contenderThread = new Thread(contender);
            contenderThread.start();
            awaitBlocked(contenderThread);
            clock.release();
            assertEquals(PickupResult.CLAIMED, owner.get(3, TimeUnit.SECONDS));
            assertEquals(PickupResult.RIDER_BUSY, contender.get(3, TimeUnit.SECONDS));
        } finally {
            clock.release();
        }
        assertEquals(Optional.of(MealStatus.AVAILABLE), kitchen.status("meal-2"));
    }

    @Test
    @Timeout(5)
    void stalledOwnerHoldsUpUnrelatedRiderEvenWhenInterrupted() throws Exception {
        GateClock clock = new GateClock(DEADLINE.minusSeconds(1));
        SynchronizedKitchen kitchen = kitchenWith(clock, "meal-1", "meal-2");
        try {
            FutureTask<PickupResult> owner = claimInThread(kitchen, "meal-1", "rider-a");
            clock.awaitOwnerInsideMonitor();
            FutureTask<PickupResult> unrelated = new FutureTask<>(() -> kitchen.claim("meal-2", "rider-b"));
            Thread unrelatedThread = new Thread(unrelated);
            unrelatedThread.start();
            awaitBlocked(unrelatedThread);
            unrelatedThread.interrupt();
            Thread.sleep(50);
            assertEquals(Thread.State.BLOCKED, unrelatedThread.getState());
            clock.release();
            assertEquals(PickupResult.CLAIMED, owner.get(3, TimeUnit.SECONDS));
            assertEquals(PickupResult.CLAIMED, unrelated.get(3, TimeUnit.SECONDS));
        } finally {
            clock.release();
        }
    }

    @Test
    void claimRejectsAtExpiryEvenWithoutCleanup() {
        SynchronizedKitchen kitchen = kitchenAt(DEADLINE);
        assertEquals(PickupResult.EXPIRED, kitchen.claim("meal-1", "rider-a"));
        assertEquals(Optional.of(MealStatus.EXPIRED), kitchen.status("meal-1"));
        assertEquals(ExpiryResult.ALREADY_EXPIRED, kitchen.expire("meal-1"));
    }

    @Test
    void completedClaimCannotLaterBeExpired() {
        MutableClock clock = new MutableClock(DEADLINE.minusSeconds(1));
        SynchronizedKitchen kitchen = new SynchronizedKitchen(clock);
        kitchen.add(new Meal("meal-1", DEADLINE));
        assertEquals(PickupResult.CLAIMED, kitchen.claim("meal-1", "rider-a"));
        clock.set(DEADLINE);
        assertEquals(ExpiryResult.ALREADY_CLAIMED, kitchen.expire("meal-1"));
        assertEquals(Optional.of(MealStatus.CLAIMED), kitchen.status("meal-1"));
    }

    @Test
    void expiryFirstPreventsClaimAndDuplicateIdsAreRejected() {
        SynchronizedKitchen kitchen = kitchenAt(DEADLINE);
        assertEquals(ExpiryResult.EXPIRED, kitchen.expire("meal-1"));
        assertEquals(PickupResult.EXPIRED, kitchen.claim("meal-1", "rider-a"));
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
                return kitchen.claim("meal-1", "rider-a");
            });
            Future<PickupResult> second = workers.submit(() -> {
                start.await();
                return kitchen.claim("meal-1", "rider-b");
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

    private static SynchronizedKitchen kitchenWith(Clock clock, String... mealIds) {
        SynchronizedKitchen kitchen = new SynchronizedKitchen(clock);
        for (String mealId : mealIds) {
            kitchen.add(new Meal(mealId, DEADLINE));
        }
        return kitchen;
    }

    private static FutureTask<PickupResult> claimInThread(SynchronizedKitchen kitchen, String mealId, String riderId) {
        FutureTask<PickupResult> task = new FutureTask<>(() -> kitchen.claim(mealId, riderId));
        new Thread(task).start();
        return task;
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadlineNanos, "thread never blocked on the monitor: " + thread.getState());
            Thread.sleep(1);
        }
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

    private static final class PerMealCasKitchen {
        private final Map<String, AtomicReference<MealStatus>> statusByMeal = new ConcurrentHashMap<>();
        private final Set<String> ridersWithMeal = ConcurrentHashMap.newKeySet();
        private final CyclicBarrier bothPassedBagCheck = new CyclicBarrier(2);

        private PerMealCasKitchen(String... mealIds) {
            for (String mealId : mealIds) {
                statusByMeal.put(mealId, new AtomicReference<>(MealStatus.AVAILABLE));
            }
        }

        private boolean claim(String mealId, String riderId) throws Exception {
            if (ridersWithMeal.contains(riderId)) {
                return false;
            }
            bothPassedBagCheck.await(3, TimeUnit.SECONDS);
            if (!statusByMeal.get(mealId).compareAndSet(MealStatus.AVAILABLE, MealStatus.CLAIMED)) {
                return false;
            }
            ridersWithMeal.add(riderId);
            return true;
        }

        private long claimedMeals() {
            return statusByMeal.values().stream().filter(status -> status.get() == MealStatus.CLAIMED).count();
        }
    }

    /** Holds the first caller at the clock read, which claim() performs inside the monitor. */
    private static final class GateClock extends Clock {
        private final Instant now;
        private final AtomicBoolean gateTaken = new AtomicBoolean();
        private final CountDownLatch ownerInside = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private GateClock(Instant now) {
            this.now = now;
        }

        private void awaitOwnerInsideMonitor() throws InterruptedException {
            assertTrue(ownerInside.await(3, TimeUnit.SECONDS));
        }

        private void release() {
            released.countDown();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (gateTaken.compareAndSet(false, true)) {
                ownerInside.countDown();
                try {
                    released.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            return now;
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
