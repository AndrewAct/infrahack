package io.infrahack.explorejavaconcurrency.part03;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A synthetic blocking handoff workload, not a benchmark of real kitchen or network I/O. */
public final class LunchRushExperiment {
    private static final int HANDOFF_PERMITS = 5;
    private static final int MAX_IN_FLIGHT = 20;
    private static final int ARRIVAL_GAP_MS = 2;
    private static final int FRESHNESS_MS = 120;

    private LunchRushExperiment() {}

    public enum Mode {
        PLATFORM,
        VIRTUAL
    }

    public record Result(
            Mode mode, int workers, int submitted, int accepted, int rejected,
            int fresh, int expired, int interrupted, int maxInFlight,
            int maxHandoffs, int maxQueueDepth, double p95QueueWaitMs,
            double p95HandoffWaitMs, double durationMs) {
        public double freshPerSecond() {
            return fresh * 1000.0 / durationMs;
        }
    }

    public static Result run(Mode mode, int workers, int requests, long seed)
            throws InterruptedException {
        if (requests <= 0 || (mode == Mode.PLATFORM && (workers <= 0 || workers >= MAX_IN_FLIGHT))) {
            throw new IllegalArgumentException("invalid workload or worker count");
        }
        ThreadPoolExecutor platform = mode == Mode.PLATFORM
                ? new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(MAX_IN_FLIGHT - workers),
                        new ThreadPoolExecutor.AbortPolicy())
                : null;
        ExecutorService executor = platform != null ? platform : Executors.newVirtualThreadPerTaskExecutor();
        Semaphore admission = new Semaphore(MAX_IN_FLIGHT);
        Semaphore handoff = new Semaphore(HANDOFF_PERMITS, true);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger fresh = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger activeHandoffs = new AtomicInteger();
        AtomicInteger maxHandoffs = new AtomicInteger();
        AtomicInteger maxQueueDepth = new AtomicInteger();
        ConcurrentLinkedQueue<Long> queueWaits = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> handoffWaits = new ConcurrentLinkedQueue<>();
        SplittableRandom random = new SplittableRandom(seed);
        long started = System.nanoTime();

        boolean finished = false;
        try {
            for (int i = 0; i < requests; i++) {
                long arrival = System.nanoTime();
                long deadline = arrival + TimeUnit.MILLISECONDS.toNanos(FRESHNESS_MS);
                int serviceMs = random.nextInt(12, 25);
                if (!admission.tryAcquire()) {
                    rejected.incrementAndGet();
                } else {
                    int current = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(current, Math::max);
                    try {
                        executor.execute(() -> {
                            try {
                                queueWaits.add(System.nanoTime() - arrival);
                                long handoffWaitStarted = System.nanoTime();
                                long remaining = deadline - handoffWaitStarted;
                                if (remaining <= 0 || !handoff.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                                    expired.incrementAndGet();
                                    return;
                                }
                                try {
                                    handoffWaits.add(System.nanoTime() - handoffWaitStarted);
                                    int active = activeHandoffs.incrementAndGet();
                                    maxHandoffs.accumulateAndGet(active, Math::max);
                                    try {
                                        Thread.sleep(serviceMs);
                                    } finally {
                                        activeHandoffs.decrementAndGet();
                                    }
                                    if (System.nanoTime() < deadline) {
                                        fresh.incrementAndGet();
                                    } else {
                                        expired.incrementAndGet();
                                    }
                                } finally {
                                    handoff.release();
                                }
                            } catch (InterruptedException e) {
                                interrupted.incrementAndGet();
                                Thread.currentThread().interrupt();
                            } finally {
                                inFlight.decrementAndGet();
                                admission.release();
                            }
                        });
                        accepted.incrementAndGet();
                        if (platform != null) {
                            maxQueueDepth.accumulateAndGet(platform.getQueue().size(), Math::max);
                        }
                    } catch (RejectedExecutionException e) {
                        inFlight.decrementAndGet();
                        admission.release();
                        rejected.incrementAndGet();
                    }
                }
                Thread.sleep(ARRIVAL_GAP_MS);
            }

            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workload did not finish");
            }
            finished = true;
        } finally {
            if (!finished) {
                executor.shutdownNow();
            }
        }
        double durationMs = (System.nanoTime() - started) / 1_000_000.0;
        return new Result(mode, workers, requests, accepted.get(), rejected.get(), fresh.get(),
                expired.get(), interrupted.get(), maxInFlight.get(), maxHandoffs.get(),
                platform == null ? -1 : maxQueueDepth.get(), percentileMs(queueWaits, 95),
                percentileMs(handoffWaits, 95), durationMs);
    }

    private static double percentileMs(ConcurrentLinkedQueue<Long> nanos, int percentile) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> values = new ArrayList<>(nanos);
        Collections.sort(values);
        int index = (int) Math.ceil(percentile * values.size() / 100.0) - 1;
        return values.get(index) / 1_000_000.0;
    }

    public static void main(String[] args) throws InterruptedException {
        record Case(Mode mode, int workers) {}
        List<Case> cases = List.of(
                new Case(Mode.PLATFORM, 2),
                new Case(Mode.PLATFORM, 8),
                new Case(Mode.PLATFORM, 16),
                new Case(Mode.VIRTUAL, 0));
        for (Case testCase : cases) {
            run(testCase.mode(), testCase.workers(), 100, 42); // Warmup; not reported.
        }
        System.out.println("mode,workers,repeat,accepted,rejected,fresh,expired,interrupted,"
                + "p95_queue_ms,p95_handoff_ms,max_queue,max_in_flight,max_handoffs,duration_ms,fresh_per_s");
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (Case testCase : cases) {
                Result result = run(testCase.mode(), testCase.workers(), 200, 42 + repeat);
                System.out.printf(java.util.Locale.ROOT,
                        "%s,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d,%d,%.2f,%.2f%n",
                        result.mode(), result.workers(), repeat, result.accepted(), result.rejected(),
                        result.fresh(), result.expired(), result.interrupted(),
                        result.p95QueueWaitMs(), result.p95HandoffWaitMs(),
                        result.maxQueueDepth(), result.maxInFlight(), result.maxHandoffs(),
                        result.durationMs(), result.freshPerSecond());
            }
        }
    }
}
