package io.infrahack.eventdispatcher;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsyncEventBus implements EventBus {
    private final ConcurrentMap<EventType, CopyOnWriteArraySet<SubscriberRegistration>> registry = new ConcurrentHashMap<>();
    private final ErrorHandler errorHandler;
    private final MetricsRecorder metrics;

    // Hashset is not thread safe, so we use the ConcurrentHashMap.newKeySet() factory method.
    private final Set<ExecutorService> knownExecutors = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AsyncEventBus(ErrorHandler errorHandler, MetricsRecorder metrics) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void register(EventType eventType, SubscriberRegistration subscriberRegistration) {
        if (closed.get()) {
            throw new IllegalStateException("EventBus is closed");
        }
        knownExecutors.add(subscriberRegistration.executor());
        registry.computeIfAbsent(eventType, k -> new CopyOnWriteArraySet<>()).add(subscriberRegistration);
    }

    @Override
    public void deregister(EventType eventType, String subscriberId) {
        Set<SubscriberRegistration> subscribers = registry.get(eventType);
        if (subscribers == null) {
            return;
        }
        subscribers.removeIf(registration -> registration.subscriberId().equals(subscriberId));

        if (subscribers.isEmpty()) {
            registry.remove(eventType, subscribers);
        }
    }

    @Override
    public DispatchResult publish(DomainEvent event) {
        if (closed.get()) {
            throw new IllegalStateException("EventBus is closed");
        }
        metrics.recordPublished(event);
        Set<SubscriberRegistration> subscribers = registry.get(event.eventType());
        if (subscribers == null || subscribers.isEmpty()) {
            metrics.recordNoSubscriber(event);
            return new DispatchResult(0, 0, 0);
        }

        int submitted = 0;
        int rejected = 0;

        for (SubscriberRegistration subscriberRegistration : subscribers) {
            try {
                subscriberRegistration.executor().execute(() -> invokeSubscriber(event, subscriberRegistration));
                metrics.recordSubmitted(event, subscriberRegistration.subscriberId());
                submitted++;
            } catch (RejectedExecutionException e) {
                metrics.recordRejected(event, subscriberRegistration.subscriberId());
                errorHandler.handle(event, subscriberRegistration.subscriberId(), e);
                rejected++;
            }
        }
        return new DispatchResult(subscribers.size(), submitted, rejected);
    }

    public void invokeSubscriber(DomainEvent event, SubscriberRegistration subscriberRegistration) {
        long startNanos = System.nanoTime();
        try {
            subscriberRegistration.subscriber().handle(event);
            metrics.recordSuccess(event, subscriberRegistration.subscriberId(), System.nanoTime() - startNanos);
        } catch (Exception e) {
            metrics.recordFailure(event, subscriberRegistration.subscriberId(), System.nanoTime() - startNanos);
            errorHandler.handle(event, subscriberRegistration.subscriberId(), e);
        }
    }

    @Override
    public void close() {
        // Ensure that close is idempotent and atomic
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (ExecutorService executor : knownExecutors) {
            executor.shutdown();
        }
    }
}
