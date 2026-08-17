package io.infrahack.ridesharedispatch.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * One row per logical dispatch command (see {@code uq_dispatch_requests_requester_key}).
 * A retried POST with the same idempotency key resolves to the same DispatchRequest
 * instance, never a new one.
 */
public record DispatchRequest(
        DispatchRequestId id,
        RequesterId requesterId,
        String idempotencyKey,
        String requestFingerprint,
        DispatchRequestStatus status,
        String serviceType,
        GeoPoint origin,
        GeoPoint destination,
        Optional<DriverId> matchedDriverId,
        Instant createdAt,
        Instant updatedAt
) {

    public DispatchRequest withStatus(DispatchRequestStatus newStatus, Optional<DriverId> matched, Instant now) {
        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "Illegal dispatch request transition %s -> %s".formatted(status, newStatus));
        }
        return new DispatchRequest(id, requesterId, idempotencyKey, requestFingerprint,
                newStatus, serviceType, origin, destination, matched, createdAt, now);
    }
}
