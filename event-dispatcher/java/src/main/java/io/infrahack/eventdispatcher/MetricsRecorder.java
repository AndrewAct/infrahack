package io.infrahack.eventdispatcher;

import java.time.Duration;

public interface MetricsRecorder {
    void recordPublished(DomainEvent event);

    void recordNoSubscriber(DomainEvent event);

    void recordSubmitted(DomainEvent event, String subscriberId);

    void recordSuccess(DomainEvent event, String subscriberId, long latencyNanos);

    void recordFailure(DomainEvent event, String subscriberId, long latencyNanos);

    void recordRejected(DomainEvent event, String subscriberId);

    void recordRetryScheduled(DomainEvent event, String subscriberId, int nextAttempt, Duration delay);

    void recordDeadLettered(DeadLetterRecord record);

    void recordQueueWait(DomainEvent event, String subscriberId, long queueWaitNanos);
}
