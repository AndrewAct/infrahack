package io.infrahack.eventdispatcher;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsyncEventBus implements EventBus {
    private final ConcurrentMap<EventType, CopyOnWriteArraySet<SubscriberRegistration>> registry = new ConcurrentHashMap<>();
    private final MetricsRecorder metrics;
    private final RetryingDeliveryExecutor deliveryExecutor;

    // Hashset is not thread safe, so we use the ConcurrentHashMap.newKeySet() factory method.
    private final Set<ExecutorService> knownExecutors = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AsyncEventBus(ErrorHandler errorHandler, MetricsRecorder metrics) {
        this(errorHandler, metrics, RetryPolicy.defaultPolicy(), new InMemoryDeadLetterSink());
    }

    public AsyncEventBus(
            ErrorHandler errorHandler,
            MetricsRecorder metrics,
            RetryPolicy retryPolicy,
            DeadLetterSink deadLetterSink) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.deliveryExecutor = new RetryingDeliveryExecutor(
                Objects.requireNonNull(errorHandler, "errorHandler"),
                metrics,
                Objects.requireNonNull(retryPolicy, "retryPolicy"),
                Objects.requireNonNull(deadLetterSink, "deadLetterSink"));
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
            DeliveryEnvelope envelope = DeliveryEnvelope.initial(event, subscriberRegistration.subscriberId());
            if (deliveryExecutor.submit(envelope, subscriberRegistration)) {
                submitted++;
            } else {
                rejected++;
            }
        }
        return new DispatchResult(subscribers.size(), submitted, rejected);
    }

    @Override
    public void close() {
        // Ensure that close is idempotent and atomic
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        deliveryExecutor.close();
        for (ExecutorService executor : knownExecutors) {
            executor.shutdown();
        }
    }
}
