package io.infrahack.ridesharedispatch.domain;

import java.time.Instant;

/**
 * A payment operation keyed by a stable {@code operationId} (typically
 * {@code "<assignmentId>:final-charge"}). Calling the provider again with the same
 * operationId -- because of a client retry or a reconciliation sweep after a timeout --
 * must never mint a second charge. See FakePaymentProvider.
 */
public record Payment(
        PaymentId id,
        AssignmentId assignmentId,
        String operationId,
        Money amount,
        PaymentStatus status,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {

    public Payment withStatus(PaymentStatus newStatus, Instant now) {
        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalStateException("Illegal payment transition %s -> %s".formatted(status, newStatus));
        }
        return new Payment(id, assignmentId, operationId, amount, newStatus, attemptCount + 1, createdAt, now);
    }
}
