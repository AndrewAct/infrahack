package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.DispatchRequestStatus;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.service.MatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #5: a stale/offline driver cannot successfully pass final matching
 * validation. See DriverOperationalState.isMatchable.
 */
class MatchingServiceTest extends AbstractIntegrationTest {

    @Autowired
    private MatchingService matchingService;

    private static final GeoPoint ORIGIN = new GeoPoint(37.7749, -122.4194);

    private DispatchRequest requestAt(GeoPoint origin) {
        return requestAt(origin, "STANDARD");
    }

    private DispatchRequest requestAt(GeoPoint origin, String serviceType) {
        return new DispatchRequest(DispatchRequestId.newId(), RequesterId.newId(), "key", "fp",
                DispatchRequestStatus.SEARCHING, serviceType, origin, origin, Optional.empty(), Instant.now(), Instant.now());
    }

    @Test
    void freshAvailableDriverIsMatched() {
        DriverId driverId = createAvailableDriverAt(new GeoPoint(37.7750, -122.4195));

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().driverId()).isEqualTo(driverId);
    }

    @Test
    void staleDriverIsExcludedFromMatching() {
        DriverId driverId = createAvailableDriverAt(new GeoPoint(37.7750, -122.4195));
        // Directly backdate lastSeen past the (short, test-configured) freshness window
        // instead of sleeping in the test -- same effect, no wall-clock wait.
        redisTemplate.opsForHash().put("driver:state:" + driverId.value(), "lastSeen",
                Long.toString(Instant.now().minusSeconds(3600).toEpochMilli()));

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isEmpty();
    }

    @Test
    void offlineDriverIsExcludedFromMatching() {
        DriverId driverId = createAvailableDriverAt(new GeoPoint(37.7750, -122.4195));
        driverService.setAvailability(driverId, false);

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isEmpty();
    }

    @Test
    void nearestEligibleDriverIsPreferredByEtaRanking() {
        // Both within the (test-configured) 3-ring / 0.02-degree-cell search radius,
        // but "far" is several cells further out than "near".
        DriverId far = createAvailableDriverAt(new GeoPoint(37.8200, -122.4400));
        DriverId near = createAvailableDriverAt(new GeoPoint(37.7760, -122.4200));

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().driverId()).isEqualTo(near);
        assertThat(outcome.get().driverId()).isNotEqualTo(far);
    }

    @Test
    void driverWithWrongServiceTypeIsNotEligible() {
        createAvailableDriverAt(new GeoPoint(37.7750, -122.4195));

        assertThat(matchingService.attemptMatch(requestAt(ORIGIN, "PREMIUM"))).isEmpty();
    }
}
