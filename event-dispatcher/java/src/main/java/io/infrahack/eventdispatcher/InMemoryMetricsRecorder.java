package io.infrahack.eventdispatcher;

import java.util.concurrent.atomic.LongAdder;

public final class InMemoryMetricsRecorder implements MetricsRecorder {
    private final LongAdder published = new LongAdder();
    private final LongAdder noSubscriber = new LongAdder();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();

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
    }

    @Override
    public void recordFailure(DomainEvent event, String subscriberId, long latencyNanos) {
        failed.increment();
    }

    @Override
    public void recordRejected(DomainEvent event, String subscriberId) {
        rejected.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                published.sum(),
                noSubscriber.sum(),
                submitted.sum(),
                succeeded.sum(),
                failed.sum(),
                rejected.sum());
    }

    public record Snapshot(
            long published,
            long noSubscriber,
            long submitted,
            long succeeded,
            long failed,
            long rejected) {
    }
}
