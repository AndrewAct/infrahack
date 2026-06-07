package io.infrahack.eventdispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RetryingDeliveryExecutor implements AutoCloseable {
    private final ErrorHandler errorHandler;
    private final MetricsRecorder metrics;
    private final RetryPolicy retryPolicy;
    private final DeadLetterSink deadLetterSink;
    private final ScheduledExecutorService retryScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RetryingDeliveryExecutor(
            ErrorHandler errorHandler,
            MetricsRecorder metrics,
            RetryPolicy retryPolicy,
            DeadLetterSink deadLetterSink) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.deadLetterSink = Objects.requireNonNull(deadLetterSink, "deadLetterSink");
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setName("event-dispatcher-retry");
            return thread;
        });
    }

    public boolean submit(DeliveryEnvelope envelope, SubscriberRegistration registration) {
        if (closed.get()) {
            return false;
        }

        DeliveryEnvelope submittedEnvelope = envelope.markSubmitted(System.nanoTime());
        try {
            registration.executor().execute(() -> invokeSubscriber(submittedEnvelope, registration));
            metrics.recordSubmitted(envelope.event(), envelope.subscriberId());
            return true;
        } catch (RejectedExecutionException e) {
            metrics.recordRejected(envelope.event(), envelope.subscriberId());
            errorHandler.handle(envelope.event(), envelope.subscriberId(), e);
            handleFailure(envelope, registration, e);
            return false;
        }
    }

    private void invokeSubscriber(DeliveryEnvelope envelope, SubscriberRegistration registration) {
        long startedAtNanos = System.nanoTime();
        if (envelope.submittedAtNanos() > 0) {
            metrics.recordQueueWait(
                    envelope.event(),
                    envelope.subscriberId(),
                    startedAtNanos - envelope.submittedAtNanos());
        }

        try {
            registration.subscriber().handle(envelope.event());
            metrics.recordSuccess(envelope.event(), envelope.subscriberId(), System.nanoTime() - startedAtNanos);
        } catch (Exception e) {
            metrics.recordFailure(envelope.event(), envelope.subscriberId(), System.nanoTime() - startedAtNanos);
            errorHandler.handle(envelope.event(), envelope.subscriberId(), e);
            handleFailure(envelope, registration, e);
        }
    }

    private void handleFailure(
            DeliveryEnvelope failedEnvelope,
            SubscriberRegistration registration,
            Exception error) {
        if (!retryPolicy.shouldRetry(failedEnvelope.attempt())) {
            DeadLetterRecord record = DeadLetterRecord.from(failedEnvelope, error);
            deadLetterSink.record(record);
            metrics.recordDeadLettered(record);
            return;
        }

        Duration delay = retryPolicy.delayAfter(failedEnvelope.attempt());
        DeliveryEnvelope nextAttempt = failedEnvelope.nextAttempt(error);
        metrics.recordRetryScheduled(nextAttempt.event(), nextAttempt.subscriberId(), nextAttempt.attempt(), delay);
        try {
            retryScheduler.schedule(() -> submit(nextAttempt, registration), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            DeadLetterRecord record = DeadLetterRecord.from(nextAttempt, e);
            deadLetterSink.record(record);
            metrics.recordDeadLettered(record);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            retryScheduler.shutdown();
        }
    }
}
