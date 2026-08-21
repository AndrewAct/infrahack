package io.infrahack.ridesharedispatch.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import io.infrahack.ridesharedispatch.repository.OutboxRepository;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One place where every metric name is defined, so a service class calls
 * {@code metrics.locationUpdatesTotal().increment()} instead of hard-coding a string
 * that a typo could silently fork into a second, orphaned series. See docs/DESIGN.md
 * "Observability" for what each signal is watching for.
 */
@Component
public class DispatchMetrics {

    private final Counter locationUpdatesTotal;
    private final Counter locationUpdatesStaleTotal;
    private final Counter dispatchRequestsTotal;
    private final Counter dispatchIdempotencyReplaysTotal;
    private final Counter matchingAttemptsTotal;
    private final Counter matchingCandidatesExamined;
    private final Counter matchingReservationConflictsTotal;
    private final Counter matchingFailuresTotal;
    private final Counter matchingTimeoutsTotal;
    private final Counter assignmentCompletedTotal;
    private final Counter outboxPublishFailuresTotal;
    private final Counter notificationDeliveriesTotal;
    private final Counter notificationDuplicateEventsTotal;
    private final Counter paymentAttemptsTotal;
    private final Counter paymentSuccessTotal;
    private final Counter paymentFailuresTotal;
    private final Counter paymentReconciliationsTotal;
    private final Timer locationUpdateLatency;
    private final Timer matchLatency;

    /** Best-effort gauge: incremented on a winning reservation, decremented on release
     *  or consumption. It does not decrement when a reservation is reclaimed purely by
     *  Redis TTL expiry (a crashed worker case), so it can drift slightly high between
     *  crashes and the next successful release -- documented, not hidden. */
    private final AtomicInteger activeReservations = new AtomicInteger(0);

    public DispatchMetrics(MeterRegistry registry, OutboxRepository outboxRepository) {
        this.locationUpdatesTotal = registry.counter("location_updates_total");
        this.locationUpdatesStaleTotal = registry.counter("location_updates_stale_total");
        this.dispatchRequestsTotal = registry.counter("dispatch_requests_total");
        this.dispatchIdempotencyReplaysTotal = registry.counter("dispatch_idempotency_replays_total");
        this.matchingAttemptsTotal = registry.counter("matching_attempts_total");
        this.matchingCandidatesExamined = registry.counter("matching_candidates_examined");
        this.matchingReservationConflictsTotal = registry.counter("matching_reservation_conflicts_total");
        this.matchingFailuresTotal = registry.counter("matching_failures_total");
        this.matchingTimeoutsTotal = registry.counter("matching_timeouts_total");
        this.assignmentCompletedTotal = registry.counter("assignment_completed_total");
        this.outboxPublishFailuresTotal = registry.counter("outbox_publish_failures_total");
        this.notificationDeliveriesTotal = registry.counter("notification_deliveries_total");
        this.notificationDuplicateEventsTotal = registry.counter("notification_duplicate_events_total");
        this.paymentAttemptsTotal = registry.counter("payment_attempts_total");
        this.paymentSuccessTotal = registry.counter("payment_success_total");
        this.paymentFailuresTotal = registry.counter("payment_failures_total");
        this.paymentReconciliationsTotal = registry.counter("payment_reconciliations_total");
        this.locationUpdateLatency = Timer.builder("location_update_latency")
                .publishPercentileHistogram().register(registry);
        this.matchLatency = Timer.builder("match_latency")
                .publishPercentileHistogram().register(registry);
        registry.gauge("active_reservations", activeReservations);
        Gauge.builder("outbox_pending", outboxRepository, OutboxRepository::countPending)
                .register(registry);
    }

    public Counter locationUpdatesTotal() {
        return locationUpdatesTotal;
    }

    public Counter locationUpdatesStaleTotal() {
        return locationUpdatesStaleTotal;
    }

    public Counter dispatchRequestsTotal() {
        return dispatchRequestsTotal;
    }

    public Counter dispatchIdempotencyReplaysTotal() {
        return dispatchIdempotencyReplaysTotal;
    }

    public Counter matchingAttemptsTotal() {
        return matchingAttemptsTotal;
    }

    public Counter matchingCandidatesExamined() {
        return matchingCandidatesExamined;
    }

    public Counter matchingReservationConflictsTotal() {
        return matchingReservationConflictsTotal;
    }

    public Counter matchingFailuresTotal() {
        return matchingFailuresTotal;
    }

    public Counter matchingTimeoutsTotal() {
        return matchingTimeoutsTotal;
    }

    public Counter assignmentCompletedTotal() {
        return assignmentCompletedTotal;
    }

    public Counter outboxPublishFailuresTotal() {
        return outboxPublishFailuresTotal;
    }

    public Counter notificationDeliveriesTotal() {
        return notificationDeliveriesTotal;
    }

    public Counter notificationDuplicateEventsTotal() {
        return notificationDuplicateEventsTotal;
    }

    public Counter paymentAttemptsTotal() {
        return paymentAttemptsTotal;
    }

    public Counter paymentSuccessTotal() {
        return paymentSuccessTotal;
    }

    public Counter paymentFailuresTotal() {
        return paymentFailuresTotal;
    }

    public Counter paymentReconciliationsTotal() {
        return paymentReconciliationsTotal;
    }

    public Timer locationUpdateLatency() {
        return locationUpdateLatency;
    }

    public Timer matchLatency() {
        return matchLatency;
    }

    public void reservationAcquired() {
        activeReservations.incrementAndGet();
    }

    public void reservationReleased() {
        activeReservations.updateAndGet(v -> Math.max(0, v - 1));
    }
}
