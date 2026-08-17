package io.infrahack.ridesharedispatch.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An offer made to exactly one candidate driver for one dispatch request. The
 * {@code reservationToken} is the value written by the atomic Redis reservation
 * ({@code SET reservation:{driverId} <token> NX EX ttl}); accept must re-check that
 * the Redis reservation still holds this exact token before it is honored.
 */
public record DispatchOffer(
        OfferId id,
        DispatchRequestId requestId,
        DriverId driverId,
        OfferStatus status,
        UUID reservationToken,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public DispatchOffer withStatus(OfferStatus newStatus, Instant now) {
        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalStateException("Illegal offer transition %s -> %s".formatted(status, newStatus));
        }
        return new DispatchOffer(id, requestId, driverId, newStatus, reservationToken, expiresAt, createdAt, now);
    }
}
