package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.repository.DispatchRequestRepository;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchRecoveryTest extends AbstractIntegrationTest {

    @Autowired private DispatchRequestService service;
    @Autowired private DispatchRequestRepository requestRepository;

    @Test
    void replayResumesARequestWhoseFirstMatchingAttemptFoundNoDriver() {
        GeoPoint origin = new GeoPoint(37.7749, -122.4194);
        RequesterId requester = RequesterId.newId();
        var command = new DispatchRequestService.CreateCommand("STANDARD", origin, origin);

        var first = service.createOrReplay(requester, "recoverable-command", command);
        assertThat(first.offer()).isEmpty();
        createAvailableDriverAt(origin);

        var replay = service.createOrReplay(requester, "recoverable-command", command);
        assertThat(replay.wasReplay()).isTrue();
        assertThat(replay.request().id()).isEqualTo(first.request().id());
        assertThat(replay.offer()).isPresent();
    }

    @Test
    void expiredLeaseOwnerCannotReleaseANewerMatchingLease() {
        GeoPoint origin = new GeoPoint(37.7749, -122.4194);
        var request = service.createOrReplay(RequesterId.newId(), "lease-command",
                new DispatchRequestService.CreateCommand("STANDARD", origin, origin)).request();
        UUID oldWorker = UUID.randomUUID();
        UUID newWorker = UUID.randomUUID();

        assertThat(requestRepository.tryClaimMatching(
                request.id(), oldWorker, Instant.now().plusSeconds(10))).isTrue();
        jdbc.update("UPDATE dispatch_requests SET matching_claimed_until = now() - interval '1 second' "
                + "WHERE request_id = ?", request.id().value());
        assertThat(requestRepository.tryClaimMatching(
                request.id(), newWorker, Instant.now().plusSeconds(10))).isTrue();

        requestRepository.releaseMatchingClaim(request.id(), oldWorker);

        assertThat(jdbc.queryForObject(
                "SELECT matching_claimed_by FROM dispatch_requests WHERE request_id = ?",
                UUID.class, request.id().value())).isEqualTo(newWorker);
    }
}
