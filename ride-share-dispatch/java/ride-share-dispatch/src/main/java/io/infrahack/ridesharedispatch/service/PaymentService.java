package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.domain.AssignmentStatus;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.Money;
import io.infrahack.ridesharedispatch.domain.Payment;
import io.infrahack.ridesharedispatch.domain.PaymentId;
import io.infrahack.ridesharedispatch.domain.PaymentStatus;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.AssignmentRepository;
import io.infrahack.ridesharedispatch.repository.DispatchRequestRepository;
import io.infrahack.ridesharedispatch.repository.OutboxRepository;
import io.infrahack.ridesharedispatch.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final long BASE_FARE_CENTS = 500;
    private static final long PER_KM_CENTS = 150;

    private final PaymentRepository paymentRepository;
    private final AssignmentRepository assignmentRepository;
    private final DispatchRequestRepository requestRepository;
    private final OutboxRepository outboxRepository;
    private final PaymentProvider paymentProvider;
    private final DispatchMetrics metrics;
    private final DispatchProperties properties;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository, AssignmentRepository assignmentRepository,
                          DispatchRequestRepository requestRepository, OutboxRepository outboxRepository,
                          PaymentProvider paymentProvider, DispatchMetrics metrics,
                          DispatchProperties properties, PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.assignmentRepository = assignmentRepository;
        this.requestRepository = requestRepository;
        this.outboxRepository = outboxRepository;
        this.paymentProvider = paymentProvider;
        this.metrics = metrics;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Durable consumer-side effect. This method performs no provider I/O, so a Kafka
     * consumer can commit it in the same DB transaction as processed_events.
     */
    public String prepareChargeForAssignment(AssignmentId assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalStateException("Assignment not found for payment: " + assignmentId));
        if (assignment.status() != AssignmentStatus.COMPLETED) {
            throw new IllegalStateException("Cannot charge incomplete assignment: " + assignmentId);
        }
        DispatchRequest request = requestRepository.findById(assignment.requestId())
                .orElseThrow(() -> new IllegalStateException("Dispatch request not found for payment: " + assignmentId));

        String operationId = assignmentId.value() + ":final-charge";
        Instant now = Instant.now();
        Payment payment = new Payment(PaymentId.newId(), assignmentId, operationId, fareFor(request),
                PaymentStatus.CREATED, 0, now, now);
        paymentRepository.insertIfAbsent(payment);
        return operationId;
    }

    /** Convenience entry point used by tests and manual recovery. */
    public void chargeForAssignment(AssignmentId assignmentId) {
        prepareChargeForAssignment(assignmentId);
        processDuePayments();
    }

    public void processDuePayments() {
        Instant now = Instant.now();
        DispatchProperties.Payment config = properties.payment();
        List<Payment> claimed = paymentRepository.claimDueBatch(
                now, config.batchSize(), config.maxAttempts(),
                now.plusSeconds(config.claimTtlSeconds()));
        for (Payment payment : claimed) {
            if (payment.attemptCount() > 1) {
                metrics.paymentReconciliationsTotal().increment();
            }
            processClaimed(payment);
        }
    }

    /** Backward-compatible name for the explicit reconciliation test seam. */
    public void reconcilePendingPayments(Instant ignored) {
        processDuePayments();
    }

    private void processClaimed(Payment payment) {
        metrics.paymentAttemptsTotal().increment();
        PaymentProvider.ChargeOutcome outcome;
        try {
            outcome = paymentProvider.charge(payment.operationId(), payment.amount());
        } catch (RuntimeException providerFailure) {
            scheduleRetryOrUnknown(payment, "provider-error");
            log.warn("payment provider call failed operationId={} attempt={}",
                    payment.operationId(), payment.attemptCount(), providerFailure);
            return;
        }

        switch (outcome) {
            case SUCCEEDED -> resolveWithEvent(payment, PaymentStatus.SUCCEEDED, "PaymentSucceeded");
            case FAILED -> resolveWithEvent(payment, PaymentStatus.FAILED, "PaymentFailed");
            case TIMEOUT -> {
                scheduleRetryOrUnknown(payment, "timeout");
                log.warn("payment uncertain outcome operationId={} attempt={}",
                        payment.operationId(), payment.attemptCount());
            }
        }
    }

    private void resolveWithEvent(Payment payment, PaymentStatus status, String eventType) {
        boolean resolved = Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            boolean changed = paymentRepository.resolve(payment.id(), payment.attemptCount(), status);
            if (changed) {
                outboxRepository.append(eventType, payment.assignmentId().value(), Map.of(
                        "assignmentId", payment.assignmentId().value().toString(),
                        "operationId", payment.operationId()));
            }
            return changed;
        }));
        if (!resolved) return;
        if (status == PaymentStatus.SUCCEEDED) metrics.paymentSuccessTotal().increment();
        if (status == PaymentStatus.FAILED) metrics.paymentFailuresTotal().increment();
    }

    private void scheduleRetryOrUnknown(Payment payment, String reason) {
        int maxAttempts = properties.payment().maxAttempts();
        if (payment.attemptCount() >= maxAttempts) {
            transactionTemplate.executeWithoutResult(tx -> {
                if (paymentRepository.resolve(payment.id(), payment.attemptCount(), PaymentStatus.UNKNOWN)) {
                    outboxRepository.append("PaymentUncertain", payment.assignmentId().value(), Map.of(
                            "assignmentId", payment.assignmentId().value().toString(),
                            "operationId", payment.operationId(), "reason", reason));
                }
            });
            metrics.paymentFailuresTotal().increment();
            return;
        }
        Instant nextAttempt = Instant.now().plus(retryDelay(payment.operationId(), payment.attemptCount()));
        paymentRepository.scheduleRetry(payment.id(), payment.attemptCount(), nextAttempt);
    }

    private Duration retryDelay(String operationId, int attempt) {
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        long base = Math.min(3600L, properties.payment().baseBackoffSeconds() * multiplier);
        long jitterBound = Math.max(1L, base / 4L);
        long jitter = Math.floorMod(operationId.hashCode(), (int) Math.min(Integer.MAX_VALUE, jitterBound));
        return Duration.ofSeconds(base + jitter);
    }

    private static Money fareFor(DispatchRequest request) {
        double distanceKm = request.origin().distanceKmTo(request.destination());
        return Money.ofCents(BASE_FARE_CENTS + Math.round(distanceKm * PER_KM_CENTS));
    }
}
