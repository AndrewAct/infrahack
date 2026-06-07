package io.infrahack.eventdispatcher;

import java.time.Instant;
import java.util.Objects;

public record DeliveryEnvelope(
        DomainEvent event,
        String subscriberId,
        int attempt,
        Instant firstSeenAt,
        Instant lastAttemptAt,
        long submittedAtNanos,
        String lastErrorClass,
        String lastErrorMessage) {

    public DeliveryEnvelope {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastAttemptAt, "lastAttemptAt");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }

    public static DeliveryEnvelope initial(DomainEvent event, String subscriberId) {
        Instant now = Instant.now();
        return new DeliveryEnvelope(event, subscriberId, 1, now, now, 0L, null, null);
    }

    public DeliveryEnvelope markSubmitted(long submittedAtNanos) {
        return new DeliveryEnvelope(
                event,
                subscriberId,
                attempt,
                firstSeenAt,
                lastAttemptAt,
                submittedAtNanos,
                lastErrorClass,
                lastErrorMessage);
    }

    public DeliveryEnvelope nextAttempt(Exception error) {
        return new DeliveryEnvelope(
                event,
                subscriberId,
                attempt + 1,
                firstSeenAt,
                Instant.now(),
                0L,
                error.getClass().getName(),
                error.getMessage());
    }
}
