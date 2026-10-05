package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.infrastructure.kafka.PaymentEventConsumer;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import io.infrahack.ridesharedispatch.service.OfferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerAtomicityTest extends AbstractIntegrationTest {

    @Autowired private DispatchRequestService dispatchRequestService;
    @Autowired private OfferService offerService;
    @Autowired private PaymentEventConsumer consumer;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void failedBusinessPreparationRollsBackTheProcessedMarker() throws Exception {
        createAvailableDriverAt(new GeoPoint(37.7749, -122.4194));
        var result = dispatchRequestService.createOrReplay(RequesterId.newId(), "consumer-atomicity",
                new DispatchRequestService.CreateCommand("STANDARD",
                        new GeoPoint(37.7749, -122.4194), new GeoPoint(37.78, -122.41)));
        Assignment assignment = offerService.accept(result.offer().orElseThrow().id());
        UUID eventId = UUID.randomUUID();
        String envelope = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(), "eventType", "AssignmentCompleted",
                "aggregateId", assignment.id().value().toString(),
                "payload", Map.of("assignmentId", assignment.id().value().toString())));

        assertThatThrownBy(() -> consumer.onMessage(envelope)).isInstanceOf(IllegalStateException.class);
        assertThat(processedCount(eventId)).isZero();

        jdbc.update("UPDATE assignments SET status = 'COMPLETED', version = version + 1, completed_at = now() "
                + "WHERE assignment_id = ?", assignment.id().value());
        consumer.onMessage(envelope);

        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE assignment_id = ?",
                Integer.class, assignment.id().value())).isEqualTo(1);
    }

    private int processedCount(UUID eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer_name = 'payment'",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }
}
