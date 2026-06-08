package io.infrahack.eventdispatcher;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class PartitionSkewDemo {
    private static final int PARTITIONS = 4;
    private static final int HOT_PARTITION = 0;
    private static final int HOT_EVENTS = 100;
    private static final int NORMAL_EVENTS_PER_PARTITION = 10;
    private static final int PROCESSING_MS = 8;

    private PartitionSkewDemo() {
    }

    public static void main(String[] args) throws Exception {
        int totalEvents = HOT_EVENTS + ((PARTITIONS - 1) * NORMAL_EVENTS_PER_PARTITION);
        InMemoryMetricsRecorder busMetrics = new InMemoryMetricsRecorder();
        AsyncEventBus eventBus = new AsyncEventBus(
                new LoggingErrorHandler(),
                busMetrics,
                RetryPolicy.noRetries(),
                new InMemoryDeadLetterSink());

        ThreadPoolExecutor routerExecutor = BoundedExecutors.fixed("partition-router", 1, totalEvents);
        PartitionedTopicSubscriber subscriber = new PartitionedTopicSubscriber(PARTITIONS, totalEvents);
        eventBus.register(
                EventType.AUTH,
                new SubscriberRegistration("payment-topic-consumer", subscriber, routerExecutor));

        long startedAtNanos = System.nanoTime();
        publishSkewedWorkload(eventBus);

        if (!subscriber.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("partition workers did not drain in time");
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        eventBus.close();
        subscriber.close();
        routerExecutor.awaitTermination(1, TimeUnit.SECONDS);

        System.out.println("workload: partition 0 receives 100 events; partitions 1-3 receive 10 each");
        System.out.println("routing rule: floorMod(partitionKey.hashCode(), 4)");
        System.out.println("total elapsed ms: " + elapsedMillis);
        System.out.println();
        System.out.println("partition | accepted | completed | rejected | maxQueueDepth | avgQueueWaitMs");
        for (int partition = 0; partition < PARTITIONS; partition++) {
            PartitionStats stats = subscriber.snapshot(partition);
            System.out.printf(
                    "%9d | %8d | %9d | %8d | %13d | %14.2f%n",
                    partition,
                    stats.accepted,
                    stats.completed,
                    stats.rejected,
                    stats.maxQueueDepth,
                    stats.avgQueueWaitMillis());
        }
        System.out.println();
        System.out.println("bus metrics: " + busMetrics.snapshot());
        System.out.println();
        System.out.println("interview takeaway: one hot partition dominates end-to-end drain time even when other partitions are idle.");
    }

    private static void publishSkewedWorkload(EventBus eventBus) {
        publishForPartition(eventBus, HOT_PARTITION, HOT_EVENTS, "hot-account");
        for (int partition = 1; partition < PARTITIONS; partition++) {
            publishForPartition(eventBus, partition, NORMAL_EVENTS_PER_PARTITION, "normal-account-" + partition);
        }
    }

    private static void publishForPartition(EventBus eventBus, int partition, int count, String label) {
        String key = keyForPartition(partition, label);
        for (int i = 0; i < count; i++) {
            eventBus.publish(DomainEvent.of(EventType.AUTH, key, Map.of(
                    "account", label,
                    "sequence", Integer.toString(i),
                    "expectedPartition", Integer.toString(partition))));
        }
    }

    private static String keyForPartition(int partition, String prefix) {
        for (int candidate = 0; candidate < 10_000; candidate++) {
            String key = prefix + "-" + candidate;
            if (partitionFor(key, PARTITIONS) == partition) {
                return key;
            }
        }
        throw new IllegalStateException("could not find key for partition " + partition);
    }

    private static int partitionFor(String partitionKey, int partitionCount) {
        return Math.floorMod(partitionKey.hashCode(), partitionCount);
    }

    private static final class PartitionedTopicSubscriber implements Subscriber, AutoCloseable {
        private final ThreadPoolExecutor[] executors;
        private final PartitionStatsRecorder[] stats;
        private final CountDownLatch drained;

        private PartitionedTopicSubscriber(int partitions, int expectedEvents) {
            this.executors = new ThreadPoolExecutor[partitions];
            this.stats = new PartitionStatsRecorder[partitions];
            this.drained = new CountDownLatch(expectedEvents);
            for (int partition = 0; partition < partitions; partition++) {
                this.executors[partition] = BoundedExecutors.fixed("topic-partition-" + partition, 1, expectedEvents);
                this.stats[partition] = new PartitionStatsRecorder();
            }
        }

        @Override
        public void handle(DomainEvent event) {
            int partition = partitionFor(event.partitionKey(), executors.length);
            PartitionStatsRecorder partitionStats = stats[partition];
            long acceptedAtNanos = System.nanoTime();
            try {
                executors[partition].execute(() -> {
                    int queueDepth = executors[partition].getQueue().size();
                    partitionStats.recordStarted(System.nanoTime() - acceptedAtNanos, queueDepth);
                    try {
                        Thread.sleep(PROCESSING_MS);
                        partitionStats.completed.increment();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        drained.countDown();
                    }
                });
                partitionStats.accepted.increment();
            } catch (RuntimeException e) {
                partitionStats.rejected.increment();
                drained.countDown();
                throw e;
            }
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return drained.await(timeout, unit);
        }

        private PartitionStats snapshot(int partition) {
            return stats[partition].snapshot();
        }

        @Override
        public void close() {
            for (ThreadPoolExecutor executor : executors) {
                executor.shutdown();
            }
        }
    }

    private static final class PartitionStatsRecorder {
        private final LongAdder accepted = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder rejected = new LongAdder();
        private final LongAdder queueWaitNanos = new LongAdder();
        private final AtomicInteger maxQueueDepth = new AtomicInteger();

        private void recordStarted(long waitNanos, int queueDepth) {
            queueWaitNanos.add(waitNanos);
            maxQueueDepth.accumulateAndGet(queueDepth, Math::max);
        }

        private PartitionStats snapshot() {
            return new PartitionStats(
                    accepted.sum(),
                    completed.sum(),
                    rejected.sum(),
                    queueWaitNanos.sum(),
                    maxQueueDepth.get());
        }
    }

    private record PartitionStats(
            long accepted,
            long completed,
            long rejected,
            long queueWaitNanos,
            int maxQueueDepth) {

        private double avgQueueWaitMillis() {
            if (completed == 0) {
                return 0.0d;
            }
            return queueWaitNanos / 1_000_000.0d / completed;
        }
    }
}
