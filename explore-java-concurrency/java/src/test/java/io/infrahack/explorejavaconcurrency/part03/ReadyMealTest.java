package io.infrahack.explorejavaconcurrency.part03;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static io.infrahack.explorejavaconcurrency.part03.ReadyMeal.ClaimResult.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ReadyMealTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterRiderCanClaimTheirReadyMealFirst(boolean virtual) throws Exception {
        Semaphore handoff = new Semaphore(1, true);
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        ReadyMeal a = new ReadyMeal("meal-A", "rider-A", deadline);
        ReadyMeal b = new ReadyMeal("meal-B", "rider-B", deadline);

        try (PendingClaim earlier = startClaim(a, "rider-A", handoff, virtual)) {
            await(() -> earlier.thread().getState() == Thread.State.TIMED_WAITING);
            assertEquals(1, handoff.availablePermits());
            assertEquals("meal-A", a.mealId());
            assertEquals("meal-B", b.mealId());
            assertTrue(b.markReady());
            assertEquals(CLAIMED, b.awaitAndClaim("rider-B", handoff));
            assertFalse(earlier.result().isDone());
            assertTrue(a.markReady());
            assertEquals(CLAIMED, earlier.result().get(2, SECONDS));
        }
        assertEquals(1, handoff.availablePermits());
    }

    @Test
    void wrongRiderCannotWaitForOrClaimAnotherMeal() throws Exception {
        Semaphore handoff = new Semaphore(1, true);
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", System.nanoTime() + SECONDS.toNanos(5));
        assertEquals(WRONG_RIDER, meal.awaitAndClaim("rider-B", handoff));
        assertTrue(meal.markReady());
        assertFalse(meal.markReady());
        assertEquals(CLAIMED, meal.awaitAndClaim("rider-A", handoff));
        assertFalse(meal.markReady());
        assertEquals(1, handoff.availablePermits());
    }

    @Test
    void competingDuplicateRequestsHaveOnlyOneSuccessfulClaim() throws Exception {
        CountDownLatch insideDecision = new CountDownLatch(1);
        CountDownLatch finishDecision = new CountDownLatch(1);
        AtomicInteger samples = new AtomicInteger();
        LongSupplier clock = () -> {
            if (samples.incrementAndGet() == 3) {
                insideDecision.countDown();
                try {
                    if (!finishDecision.await(2, SECONDS)) {
                        throw new AssertionError("claim decision was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return 0;
        };
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", SECONDS.toNanos(5), clock);
        Semaphore handoff = new Semaphore(2, true);
        meal.markReady();
        try (PendingClaim first = startClaim(meal, "rider-A", handoff, true)) {
            assertTrue(insideDecision.await(2, SECONDS));
            try (PendingClaim second = startClaim(meal, "rider-A", handoff, true)) {
                try {
                    await(() -> second.thread().getState() == Thread.State.BLOCKED || second.result().isDone());
                    assertEquals(Thread.State.BLOCKED, second.thread().getState());
                    assertEquals(0, handoff.availablePermits());
                } finally {
                    finishDecision.countDown();
                }
                assertEquals(CLAIMED, first.result().get(2, SECONDS));
                assertEquals(ALREADY_CLAIMED, second.result().get(2, SECONDS));
            }
        } finally {
            finishDecision.countDown();
        }
        assertEquals(2, handoff.availablePermits());
    }

    @Test
    void expiryDuringReadinessWaitIsRecheckedBeforeTakingCapacity() throws Exception {
        AtomicLong now = new AtomicLong();
        long deadline = SECONDS.toNanos(5);
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", deadline, now::get);
        Semaphore handoff = new Semaphore(1, true);
        try (PendingClaim pending = startClaim(meal, "rider-A", handoff, true)) {
            await(() -> pending.thread().getState() == Thread.State.TIMED_WAITING);
            now.set(deadline);
            meal.markReady();
            assertEquals(EXPIRED, pending.result().get(2, SECONDS));
        }
        assertEquals(1, handoff.availablePermits());
    }

    @Test
    void expiryDuringCapacityWaitIsRecheckedInsideClaimDecision() throws Exception {
        AtomicLong now = new AtomicLong();
        long deadline = SECONDS.toNanos(5);
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", deadline, now::get);
        meal.markReady();
        Semaphore handoff = new Semaphore(1, true);
        handoff.acquire();
        try (PendingClaim pending = startClaim(meal, "rider-A", handoff, true)) {
            try {
                await(handoff::hasQueuedThreads);
                now.set(deadline);
            } finally {
                handoff.release();
            }
            assertEquals(EXPIRED, pending.result().get(2, SECONDS));
        }
        assertEquals(1, handoff.availablePermits());
    }

    @Test
    void readinessWaitTimesOutAndLateReadinessCannotReviveExpiredMeal() throws Exception {
        Semaphore handoff = new Semaphore(1, true);
        AtomicLong now = new AtomicLong();
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", 20_000_000, now::get);
        assertEquals(EXPIRED, meal.awaitAndClaim("rider-A", handoff));
        now.set(20_000_000);
        assertTrue(meal.markReady());
        assertEquals(EXPIRED, meal.awaitAndClaim("rider-A", handoff));
        assertEquals(1, handoff.availablePermits());
    }

    @Test
    void capacityWaitTimesOutWithoutReleasingSomeoneElsesPermit() throws Exception {
        Semaphore handoff = new Semaphore(1, true);
        handoff.acquire();
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", 20_000_000, () -> 0);
        meal.markReady();
        try {
            assertEquals(EXPIRED, meal.awaitAndClaim("rider-A", handoff));
            assertEquals(0, handoff.availablePermits());
        } finally {
            handoff.release();
        }
        assertEquals(1, handoff.availablePermits());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedWaitDoesNotCancelMealReadinessOrLeakCapacity(boolean readyFirst) throws Exception {
        Semaphore handoff = new Semaphore(1, true);
        ReadyMeal meal = new ReadyMeal("meal-A", "rider-A", System.nanoTime() + SECONDS.toNanos(5));
        if (readyFirst) {
            meal.markReady();
            handoff.acquire();
        }
        try (PendingClaim pending = startClaim(meal, "rider-A", handoff, true)) {
            try {
                await(() -> readyFirst ? handoff.hasQueuedThreads()
                        : pending.thread().getState() == Thread.State.TIMED_WAITING);
                pending.thread().interrupt();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> pending.result().get(2, SECONDS));
                assertInstanceOf(InterruptedException.class, failure.getCause());
            } finally {
                if (readyFirst) {
                    handoff.release();
                }
            }
        }
        if (!readyFirst) {
            meal.markReady();
        }
        assertEquals(1, handoff.availablePermits());
        assertEquals(CLAIMED, meal.awaitAndClaim("rider-A", handoff));
        assertEquals(1, handoff.availablePermits());
    }

    private static PendingClaim startClaim(ReadyMeal meal, String riderId, Semaphore handoff,
                                           boolean virtual) {
        FutureTask<ReadyMeal.ClaimResult> task = new FutureTask<>(() -> meal.awaitAndClaim(riderId, handoff));
        Thread thread = virtual ? Thread.ofVirtual().unstarted(task) : Thread.ofPlatform().unstarted(task);
        thread.start();
        return new PendingClaim(thread, task);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("expected concurrency boundary was not reached");
            }
            LockSupport.parkNanos(1_000_000);
        }
    }

    private record PendingClaim(Thread thread, FutureTask<ReadyMeal.ClaimResult> result)
            implements AutoCloseable {
        @Override
        public void close() throws InterruptedException {
            thread.interrupt();
            thread.join(2_000);
            assertFalse(thread.isAlive(), "claim task did not stop");
        }
    }
}
