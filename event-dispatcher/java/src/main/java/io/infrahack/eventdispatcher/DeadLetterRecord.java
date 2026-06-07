package io.infrahack.eventdispatcher;

import java.time.Instant;
import java.util.Objects;

public record DeadLetterRecord(
        DomainEvent event,
        String subscriberId,
        int attempts,
        Instant firstSeenAt,
        Instant deadLetteredAt,
        String finalErrorClass,
        String finalErrorMessage) {

    public DeadLetterRecord {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(deadLetteredAt, "deadLetteredAt");
        Objects.requireNonNull(finalErrorClass, "finalErrorClass");
        if (attempts <= 0) {
            throw new IllegalArgumentException("attempts must be positive");
        }
    }

    public static DeadLetterRecord from(DeliveryEnvelope envelope, Exception error) {
        return new DeadLetterRecord(
                envelope.event(),
                envelope.subscriberId(),
                envelope.attempt(),
                envelope.firstSeenAt(),
                Instant.now(),
                error.getClass().getName(),
                error.getMessage());
    }
}
