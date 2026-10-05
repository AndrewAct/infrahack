package io.infrahack.ridesharedispatch.infrastructure.kafka;

public final class KafkaTopics {

    /** Single topic for all domain events; see DomainEventEnvelope for why. */
    public static final String DISPATCH_EVENTS = "ride-share-dispatch.events";

    private KafkaTopics() {
    }
}
