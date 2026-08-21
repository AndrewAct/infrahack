package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.domain.exception.IdempotencyConflictException;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant #2: one logical dispatch command with one idempotency key creates at most
 * one DispatchRequest. See docs/DESIGN.md "Idempotency lifecycle".
 */
class DispatchRequestIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchRequestService dispatchRequestService;

    private static DispatchRequestService.CreateCommand aCommand() {
        return new DispatchRequestService.CreateCommand(
                "STANDARD", new GeoPoint(37.7749, -122.4194), new GeoPoint(37.7849, -122.4094));
    }

    @Test
    void sameIdempotencyKeyReturnsSameDispatchRequest() {
        RequesterId requesterId = RequesterId.newId();
        String idempotencyKey = "cmd-" + UUID.randomUUID();

        DispatchRequestService.CreateResult first = dispatchRequestService.createOrReplay(requesterId, idempotencyKey, aCommand());
        DispatchRequestService.CreateResult second = dispatchRequestService.createOrReplay(requesterId, idempotencyKey, aCommand());

        assertThat(first.wasReplay()).isFalse();
        assertThat(second.wasReplay()).isTrue();
        assertThat(second.request().id()).isEqualTo(first.request().id());

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM dispatch_requests WHERE requester_id = ? AND idempotency_key = ?",
                Integer.class, requesterId.value(), idempotencyKey);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        RequesterId requesterId = RequesterId.newId();
        String idempotencyKey = "cmd-" + UUID.randomUUID();

        dispatchRequestService.createOrReplay(requesterId, idempotencyKey, aCommand());

        DispatchRequestService.CreateCommand differentPayload = new DispatchRequestService.CreateCommand(
                "STANDARD", new GeoPoint(10.0, 10.0), new GeoPoint(11.0, 11.0));

        assertThatThrownBy(() -> dispatchRequestService.createOrReplay(requesterId, idempotencyKey, differentPayload))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void differentRequestersCanReuseTheSameIdempotencyKeyLiteral() {
        String idempotencyKey = "cmd-shared-literal";

        DispatchRequestService.CreateResult a = dispatchRequestService.createOrReplay(RequesterId.newId(), idempotencyKey, aCommand());
        DispatchRequestService.CreateResult b = dispatchRequestService.createOrReplay(RequesterId.newId(), idempotencyKey, aCommand());

        assertThat(a.request().id()).isNotEqualTo(b.request().id());
    }
}
