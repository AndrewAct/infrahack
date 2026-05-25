package io.infrahack.eventdispatcher;

public interface MetricsRecorder {
    void recordPublished(DomainEvent event);

    void recordNoSubscriber(DomainEvent event);

    void recordSubmitted(DomainEvent event, String subscriberId);

    void recordSuccess(DomainEvent event, String subscriberId, long latencyNanos);

    void recordFailure(DomainEvent event, String subscriberId, long latencyNanos);

    void recordRejected(DomainEvent event, String subscriberId);
}
