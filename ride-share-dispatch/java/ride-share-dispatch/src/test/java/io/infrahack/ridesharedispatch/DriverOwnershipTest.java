package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import io.infrahack.ridesharedispatch.service.MatchingService;
import io.infrahack.ridesharedispatch.service.OfferService;
import io.infrahack.ridesharedispatch.service.SpatialIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverOwnershipTest extends AbstractIntegrationTest {

    private static final GeoPoint ORIGIN = new GeoPoint(37.7749, -122.4194);

    @Autowired private DispatchRequestService dispatchRequestService;
    @Autowired private OfferService offerService;
    @Autowired private MatchingService matchingService;
    @Autowired private SpatialIndex spatialIndex;

    @Test
    void occupiedDriverCannotBeMadeAvailableOrReservedAgain() {
        var driverId = createAvailableDriverAt(ORIGIN);
        var first = dispatchRequestService.createOrReplay(RequesterId.newId(), "first",
                new DispatchRequestService.CreateCommand("STANDARD", ORIGIN, ORIGIN));
        Assignment assignment = offerService.accept(first.offer().orElseThrow().id());

        assertThatThrownBy(() -> driverService.setAvailability(driverId, true))
                .isInstanceOf(ConflictException.class);

        // Even if discovery is stale and still returns this driver, commit-time Lua checks
        // activeAssignmentId and refuses a second reservation.
        spatialIndex.upsert(driverId, ORIGIN);
        var second = dispatchRequestService.createOrReplay(RequesterId.newId(), "second",
                new DispatchRequestService.CreateCommand("STANDARD", ORIGIN, ORIGIN));
        assertThat(second.offer()).isEmpty();
        assertThat(redisTemplate.opsForHash().get("driver:state:" + driverId.value(), "activeAssignmentId"))
                .isEqualTo(assignment.id().value().toString());
    }
}
