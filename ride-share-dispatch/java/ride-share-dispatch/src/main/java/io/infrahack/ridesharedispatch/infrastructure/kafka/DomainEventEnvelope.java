package io.infrahack.ridesharedispatch.infrastructure.kafka;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * The wire shape published for every outbox row. One topic carries every event type;
 * consumers filter on {@code eventType} rather than each getting a dedicated topic --
 * simple enough for a one-day MVP, and it keeps ordering per aggregate (partitioned by
 * {@code aggregateId} as the record key) without needing several topics kept in sync.
 */
public record DomainEventEnvelope(UUID eventId, String eventType, UUID aggregateId, Map<String, Object> payload) {

    @SuppressWarnings("unchecked")
    public static DomainEventEnvelope parse(String json, ObjectMapper objectMapper) throws Exception {
        Map<String, Object> raw = objectMapper.readValue(json, Map.class);
        return new DomainEventEnvelope(
                UUID.fromString(raw.get("eventId").toString()),
                raw.get("eventType").toString(),
                UUID.fromString(raw.get("aggregateId").toString()),
                (Map<String, Object>) raw.get("payload"));
    }
}
