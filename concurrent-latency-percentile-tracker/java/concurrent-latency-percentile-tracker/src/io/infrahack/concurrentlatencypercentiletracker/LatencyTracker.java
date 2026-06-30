package io.infrahack.concurrentlatencypercentiletracker;

import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Concurrent latency percentile tracker backed by time buckets.
 *
 * <p>If bucketSizeMs is 1, arbitrary [start, end] queries are exact for
 * millisecond timestamps. If bucketSizeMs is larger, queries include whole
 * buckets and are approximate at the two window edges.
 */
public class LatencyTracker {
    // When write reaches this threshold, clean up the old buckets.
    private static final long CLEAN_UP_EVERY_WRITES = 1024;

    // Granularity of the time buckets: every 1 millisecond.
    private final long bucketSizeMs;
    private final long retentionMs;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastBucketStart = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong writeCount = new AtomicLong(0);

    public LatencyTracker(long bucketSizeMs, long retentionMs) {
        if (bucketSizeMs <= 0) {
            throw new IllegalArgumentException("bucketSizeMs must be greater than 0: " + bucketSizeMs);
        }
        if (retentionMs <= 0) {
            throw new IllegalArgumentException("retentionMs must be greater than 0: " + retentionMs);
        }
        this.bucketSizeMs = bucketSizeMs;
        this.retentionMs = retentionMs;
    }

    public void addSample(long timestampMs, int latencyMs) {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("Latency must be non-negative: " + latencyMs);
        }
        long bucketStart = bucketStart(timestampMs);
        advanceLatestBucket(bucketStart);
        
        long latest = lastBucketStart.get();
        if (latest != Long.MIN_VALUE && bucketStart < latest - retentionMs) {
            return;
        }
        buckets.computeIfAbsent(bucketStart, Bucket::new).add(latencyMs);
        if (writeCount.incrementAndGet() % CLEAN_UP_EVERY_WRITES == 0) {
            cleanUpOldBuckets();
        }
    }

    public OptionalInt getPercentile(long startTimestampMs, long endTimestampMs, double percentile) {
        if (startTimestampMs > endTimestampMs) {
            throw new IllegalArgumentException("startTimestampMs must be less than endTimestampMs: " + startTimestampMs + " " + "endTimestampMs: " + endTimestampMs);
        }
        if (percentile <= 0.0 && percentile > 100.0) {
            throw new IllegalArgumentException("percentile must be less than or equal to 100: " + percentile);
        }
        long firstBucket = bucketStart(startTimestampMs);
        long lastBucket = bucketStart(endTimestampMs);
        NavigableMap<Integer, Long> mergedHistogram = new TreeMap<>();
        long total = 0;
        for (Bucket bucket: buckets.values()) {
            if (bucket.startMs >= firstBucket && bucket.startMs <= lastBucket) {
                total += bucket.snapshotInto(mergedHistogram);
            }
        }
        if (total == 0) {
            return OptionalInt.empty();
        }
        long rank = (long) Math.ceil(total * percentile / 100.0);
        long seen = 0;
        for (Map.Entry<Integer, Long> entry: mergedHistogram.entrySet()) {
            seen += entry.getValue();
            if (seen >= rank) {
                return OptionalInt.of(entry.getKey());
            }
        }
        throw new IllegalArgumentException("Histogram total did not match aggregated counts");
    }

    public int retainedBucketCount() {
        return buckets.size();
    }

    private long bucketStart(long timestampMs) {
        return Math.floorDiv(timestampMs, bucketSizeMs) * bucketSizeMs;
    }

    private void advanceLatestBucket(long bucketStart) {
        long current;
        while (bucketStart > (current = lastBucketStart.get())) {
            if (lastBucketStart.compareAndSet(current, bucketStart)) {
                return;
            }
        }
    }

    private void cleanUpOldBuckets() {
        long latest = lastBucketStart.get();
        if (latest == Long.MIN_VALUE) {
            return;
        }
        long minRetainedBucket = latest - retentionMs;
        buckets.keySet().removeIf(bucketStart -> bucketStart < minRetainedBucket);
    }

    private static final class Bucket {
        private final long startMs;
        private final ConcurrentHashMap<Integer, LongAdder> countsByLatency = new ConcurrentHashMap<>();
        private final LongAdder total = new LongAdder();

        private Bucket(long startMs) {
            this.startMs = startMs;
        }

        private void add(int latencyMs) {
            countsByLatency.computeIfAbsent(latencyMs, k -> new LongAdder()).increment();
            total.increment();
        }

        /**
         * Get the total number of items in the map, including the total number
         * @param destination The destination map to store the snapshot. It counts the count of every latency in all buckets
         * @return
         */
        private long snapshotInto(NavigableMap<Integer, Long> destination) {
            long bucketTotal = 0;
            for (Map.Entry<Integer, LongAdder> entry : countsByLatency.entrySet()) {
                long count = entry.getValue().sum();
                if (count > 0) {
                    destination.merge(entry.getKey(), count, Long::sum);
                    bucketTotal += count;
                }
            }
            return bucketTotal;
        }
    }
}
