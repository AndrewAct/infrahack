package io.infrahack.eventdispatcher;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

public final class InMemoryMetricsRecorder implements MetricsRecorder {
    private final LongAdder published = new LongAdder();
    private final LongAdder noSubscriber = new LongAdder();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder retryScheduled = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();
    private final LongAdder handlerLatencyNanos = new LongAdder();
    private final LongAdder queueWaitNanos = new LongAdder();

    @Override
    public void recordPublished(DomainEvent event) {
        published.increment();
    }

    @Override
    public void recordNoSubscriber(DomainEvent event) {
        noSubscriber.increment();
    }

    @Override
    public void recordSubmitted(DomainEvent event, String subscriberId) {
        submitted.increment();
    }

    @Override
    public void recordSuccess(DomainEvent event, String subscriberId, long latencyNanos) {
        succeeded.increment();
        handlerLatencyNanos.add(latencyNanos);
    }

    @Override
    public void recordFailure(DomainEvent event, String subscriberId, long latencyNanos) {
        failed.increment();
        handlerLatencyNanos.add(latencyNanos);
    }

    @Override
    public void recordRejected(DomainEvent event, String subscriberId) {
        rejected.increment();
    }

    @Override
    public void recordRetryScheduled(DomainEvent event, String subscriberId, int nextAttempt, Duration delay) {
        retryScheduled.increment();
    }

    @Override
    public void recordDeadLettered(DeadLetterRecord record) {
        deadLettered.increment();
    }

    @Override
    public void recordQueueWait(DomainEvent event, String subscriberId, long queueWaitNanos) {
        this.queueWaitNanos.add(queueWaitNanos);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                published.sum(),
                noSubscriber.sum(),
                submitted.sum(),
                succeeded.sum(),
                failed.sum(),
                rejected.sum(),
                retryScheduled.sum(),
                deadLettered.sum(),
                handlerLatencyNanos.sum(),
                queueWaitNanos.sum());
    }

    public record Snapshot(
            long published,
            long noSubscriber,
            long submitted,
            long succeeded,
            long failed,
            long rejected,
            long retryScheduled,
            long deadLettered,
            long handlerLatencyNanos,
            long queueWaitNanos) {
    }
}
