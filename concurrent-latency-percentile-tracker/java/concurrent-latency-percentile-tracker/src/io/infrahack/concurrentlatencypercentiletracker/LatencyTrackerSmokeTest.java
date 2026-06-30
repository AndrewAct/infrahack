package io.infrahack.concurrentlatencypercentiletracker;

import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LatencyTrackerSmokeTest {
    public static void main(String[] args) throws Exception {
        verifiesPercentiles();
        verifiesConcurrentWriters();
    }

    private static void verifiesPercentiles() {
        LatencyTracker tracker = new LatencyTracker(1, 60_000);
        tracker.addSample(1_000, 10);
        tracker.addSample(1_001, 20);
        tracker.addSample(1_002, 30);
        tracker.addSample(1_003, 40);

        assertEquals(20, tracker.getPercentile(1_000, 1_003, 50));
        assertEquals(40, tracker.getPercentile(1_000, 1_003, 90));
        assertEquals(30, tracker.getPercentile(1_001, 1_002, 100));
        assertEmpty(tracker.getPercentile(2_000, 3_000, 95));
    }

    private static void verifiesConcurrentWriters() throws Exception {
        LatencyTracker tracker = new LatencyTracker(1, 60_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 10_000; i++) {
            final int sample = i;
            pool.submit(() -> tracker.addSample(10_000 + sample, sample % 100));
        }

        pool.shutdown();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for writers");
        }

        assertEquals(49, tracker.getPercentile(10_000, 19_999, 50));
        assertEquals(99, tracker.getPercentile(10_000, 19_999, 100));
    }

    private static void assertEquals(int expected, OptionalInt actual) {
        if (actual.isEmpty() || actual.getAsInt() != expected) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void assertEmpty(OptionalInt actual) {
        if (actual.isPresent()) {
            throw new AssertionError("Expected empty result, got " + actual.getAsInt());
        }
    }
}

