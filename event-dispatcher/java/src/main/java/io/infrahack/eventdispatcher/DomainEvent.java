package io.infrahack.eventdispatcher;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/*
 * DomainEvent represents a single event in the system.
 * It encapsulates the event's unique identifier, type, creation timestamp, partition key, and attributes.
 * Note: we didn't use partition in the real case (at least not in the demo). It's left for future enhancements.
 */
public record DomainEvent(
        String eventId,
        EventType eventType,
        Instant createdAt,
        String partitionKey,
        Map<String, String> attributes) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(attributes, "attributes");
    }

    // Some trick: by using `of`, we can use `of.DomainEvent` instead of `new DomainEvent()` directly
    public static DomainEvent of(EventType eventType, String partitionKey, Map<String, String> attributes) {
        return new DomainEvent(UUID.randomUUID().toString(), eventType, Instant.now(), partitionKey, attributes);
    }
}
