package io.infrahack.ridesharedispatch.infrastructure.kafka;

import tools.jackson.databind.ObjectMapper;
import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.UUID;

/**
 * The other half of the transactional outbox (see OutboxRepository). Runs on a fixed
 * poll, publishes whatever is unpublished, and marks each row published only after
 * Kafka has acknowledged it. If Kafka is unavailable, rows simply accumulate as
 * unpublished -- durable, ordered by creation time, and picked up on the next tick as
 * soon as Kafka comes back. Nothing is lost; docs/DESIGN.md "Failure modes" calls this
 * out explicitly. A send that succeeds but whose acknowledgment is lost/timed-out will
 * be retried and can be redelivered -- that is exactly the at-least-once contract
 * downstream consumers are built to tolerate (see PaymentEventConsumer, NotificationEventConsumer).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_ACK_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DispatchProperties properties;
    private final DispatchMetrics metrics;
    private final UUID workerId = UUID.randomUUID();

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                            ObjectMapper objectMapper, DispatchProperties properties, DispatchMetrics metrics) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${dispatch.outbox.poll-interval-ms}")
    public void publishPendingBatch() {
        List<OutboxRepository.PendingEvent> pending = outboxRepository.claimUnpublishedBatch(
                workerId, properties.outbox().batchSize(),
                Instant.now().plusSeconds(properties.outbox().claimTtlSeconds()));
        for (OutboxRepository.PendingEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxRepository.PendingEvent event) {
        try {
            String envelopeJson = buildEnvelopeJson(event);
            kafkaTemplate.send(KafkaTopics.DISPATCH_EVENTS, event.aggregateId().toString(), envelopeJson)
                    .get(SEND_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            outboxRepository.markPublished(event.eventId(), workerId);
        } catch (Exception e) {
            outboxRepository.releaseClaim(event.eventId(), workerId);
            metrics.outboxPublishFailuresTotal().increment();
            log.warn("outbox publish failed eventId={} eventType={}, will retry next poll",
                    event.eventId(), event.eventType(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildEnvelopeJson(OutboxRepository.PendingEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.payloadJson(), Map.class);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", event.eventType());
        envelope.put("aggregateId", event.aggregateId().toString());
        envelope.put("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }
}
