package io.infrahack.explorejavaconcurrency.part02;

import io.infrahack.explorejavaconcurrency.kitchen.MealStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VersionedMealTest {
    private static final Instant DEADLINE = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    @Timeout(5)
    void onlyOneMoveCanCommitAgainstOneObservedSnapshot() throws Exception {
        VersionedMeal meal = mealAt(Clock.fixed(DEADLINE.minusSeconds(1), ZoneOffset.UTC));
        MealSnapshot observed = meal.snapshot();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<TransitionResult> shelf = workers.submit(() -> {
                start.await();
                return meal.tryMove(observed, "shelf", DEADLINE.plusSeconds(30));
            });
            Future<TransitionResult> warmer = workers.submit(() -> {
                start.await();
                return meal.tryMove(observed, "warmer", DEADLINE.plusSeconds(60));
            });
            start.countDown();
            TransitionResult first = shelf.get(3, TimeUnit.SECONDS);
            TransitionResult second = warmer.get(3, TimeUnit.SECONDS);
            assertEquals(1, (first == TransitionResult.MOVED ? 1 : 0)
                    + (second == TransitionResult.MOVED ? 1 : 0));
            assertEquals(1, (first == TransitionResult.STALE ? 1 : 0)
                    + (second == TransitionResult.STALE ? 1 : 0));
        }
        assertEquals(1, meal.snapshot().version());
    }

    @Test
    void oldExpiryTaskCannotEraseAMove() {
        MutableClock clock = new MutableClock(DEADLINE.minusSeconds(1));
        VersionedMeal meal = mealAt(clock);
        long oldVersion = meal.snapshot().version();
        assertEquals(TransitionResult.MOVED,
                meal.tryMove(meal.snapshot(), "warmer", DEADLINE.plusSeconds(60)));
        clock.set(DEADLINE);
        assertEquals(TransitionResult.STALE, meal.expireIfVersion(oldVersion));
        assertEquals("warmer", meal.snapshot().container());
        assertEquals(MealStatus.AVAILABLE, meal.snapshot().status());
        assertEquals(TransitionResult.NOT_DUE, meal.expireIfVersion(meal.snapshot().version()));
        clock.set(DEADLINE.plusSeconds(60));
        assertEquals(TransitionResult.EXPIRED, meal.expireIfVersion(meal.snapshot().version()));
    }

    @Test
    void aLaterTimestampCanChooseAStaleBusinessAction() {
        LastWriteWins fixture = new LastWriteWins("shelf", 10);
        fixture.apply("warmer", 20); // A legitimate transfer.
        fixture.apply("expired", 30); // An old cleanup task stamped when it finally ran.
        assertEquals("expired", fixture.value());
    }

    @Test
    void movingAfterTheDeadlineIsRejectedWithoutCleanup() {
        VersionedMeal meal = mealAt(Clock.fixed(DEADLINE, ZoneOffset.UTC));
        assertEquals(TransitionResult.PAST_DEADLINE,
                meal.tryMove(meal.snapshot(), "warmer", DEADLINE.plusSeconds(60)));
        assertEquals(0, meal.snapshot().version());
    }

    private static VersionedMeal mealAt(Clock clock) {
        return new VersionedMeal(
                new MealSnapshot("meal-1", "shelf", DEADLINE, MealStatus.AVAILABLE, 0), clock);
    }

    private static final class LastWriteWins {
        private String value;
        private long timestamp;

        private LastWriteWins(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        private void apply(String next, long at) {
            if (at > timestamp) {
                value = next;
                timestamp = at;
            }
        }

        private String value() {
            return value;
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
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
