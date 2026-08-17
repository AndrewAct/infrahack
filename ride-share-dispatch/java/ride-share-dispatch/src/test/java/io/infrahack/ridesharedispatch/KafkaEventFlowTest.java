package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.infrastructure.kafka.KafkaTopics;
import io.infrahack.ridesharedispatch.service.AssignmentService;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import io.infrahack.ridesharedispatch.service.OfferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end through the real path: AssignmentService.complete -&gt; transactional
 * outbox -&gt; OutboxPublisher -&gt; Kafka -&gt; PaymentEventConsumer / NotificationEventConsumer.
 * Also covers invariant #7: a duplicate event delivery does not duplicate the business
 * side effect, by publishing the exact same envelope a second time and confirming the
 * consumers' processed_events dedup absorbs it.
 */
class KafkaEventFlowTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchRequestService dispatchRequestService;
    @Autowired
    private OfferService offerService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void completingAnAssignmentEventuallyChargesPaymentAndDeliversOneNotification() {
        createAvailableAgentAt(new GeoPoint(37.7750, -122.4195));
        DispatchRequestService.CreateCommand command = new DispatchRequestService.CreateCommand(
                "STANDARD", new GeoPoint(37.7749, -122.4194), new GeoPoint(37.7849, -122.4094));
        DispatchRequestService.CreateResult result = dispatchRequestService.createOrReplay(
                RequesterId.newId(), "key-" + UUID.randomUUID(), command);
        Assignment assignment = offerService.accept(result.offer().orElseThrow().id());
        assignmentService.start(assignment.id());
        assignmentService.complete(assignment.id());

        String operationId = assignment.id().value() + ":final-charge";
        // ignoreExceptions(): until the outbox publisher and consumer have had their
        // first poll, these rows do not exist yet and queryForObject throws
        // EmptyResultDataAccessException -- that is an expected intermediate state
        // while polling, not a test failure.
        await().atMost(20, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM payments WHERE operation_id = ?", String.class, operationId);
            assertThat(status).isEqualTo("SUCCEEDED");
        });
        await().atMost(20, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM notification_deliveries WHERE recipient_id = ?",
                    Integer.class, assignment.requesterId().value());
            assertThat(count).isEqualTo(1);
        });

        // Simulate Kafka redelivering the exact same AssignmentCompleted record.
        Map<String, Object> outboxRow = jdbc.queryForMap(
                "SELECT event_id, aggregate_id, payload::text AS payload FROM outbox_events "
                        + "WHERE event_type = 'AssignmentCompleted' AND aggregate_id = ?",
                assignment.id().value());
        republish(outboxRow);

        // Give the duplicate a chance to be (mis)processed, then assert nothing changed.
        await().pollDelay(3, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer paymentRows = jdbc.queryForObject(
                    "SELECT count(*) FROM payments WHERE operation_id = ?", Integer.class, operationId);
            assertThat(paymentRows).isEqualTo(1);
            Integer notificationRows = jdbc.queryForObject(
                    "SELECT count(*) FROM notification_deliveries WHERE recipient_id = ?",
                    Integer.class, assignment.requesterId().value());
            assertThat(notificationRows).isEqualTo(1);
        });
    }

    @SuppressWarnings("unchecked")
    private void republish(Map<String, Object> outboxRow) {
        try {
            Map<String, Object> payload = objectMapper.readValue(outboxRow.get("payload").toString(), Map.class);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", outboxRow.get("event_id").toString());
            envelope.put("eventType", "AssignmentCompleted");
            envelope.put("aggregateId", outboxRow.get("aggregate_id").toString());
            envelope.put("payload", payload);
            kafkaTemplate.send(KafkaTopics.DISPATCH_EVENTS, outboxRow.get("aggregate_id").toString(),
                    objectMapper.writeValueAsString(envelope)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
