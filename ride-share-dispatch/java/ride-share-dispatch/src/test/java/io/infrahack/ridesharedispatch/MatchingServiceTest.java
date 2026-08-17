package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.AgentId;
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
 * Invariant #5: a stale/offline agent cannot successfully pass final matching
 * validation. See AgentOperationalState.isMatchable.
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
    void freshAvailableAgentIsMatched() {
        AgentId agentId = createAvailableAgentAt(new GeoPoint(37.7750, -122.4195));

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().agentId()).isEqualTo(agentId);
    }

    @Test
    void staleAgentIsExcludedFromMatching() {
        AgentId agentId = createAvailableAgentAt(new GeoPoint(37.7750, -122.4195));
        // Directly backdate lastSeen past the (short, test-configured) freshness window
        // instead of sleeping in the test -- same effect, no wall-clock wait.
        redisTemplate.opsForHash().put("agent:state:" + agentId.value(), "lastSeen",
                Long.toString(Instant.now().minusSeconds(3600).toEpochMilli()));

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isEmpty();
    }

    @Test
    void offlineAgentIsExcludedFromMatching() {
        AgentId agentId = createAvailableAgentAt(new GeoPoint(37.7750, -122.4195));
        agentService.setAvailability(agentId, false);

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isEmpty();
    }

    @Test
    void nearestEligibleAgentIsPreferredByEtaRanking() {
        // Both within the (test-configured) 3-ring / 0.02-degree-cell search radius,
        // but "far" is several cells further out than "near".
        AgentId far = createAvailableAgentAt(new GeoPoint(37.8200, -122.4400));
        AgentId near = createAvailableAgentAt(new GeoPoint(37.7760, -122.4200));

        Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(requestAt(ORIGIN));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().agentId()).isEqualTo(near);
        assertThat(outcome.get().agentId()).isNotEqualTo(far);
    }

    @Test
    void driverWithWrongServiceTypeIsNotEligible() {
        createAvailableAgentAt(new GeoPoint(37.7750, -122.4195));

        assertThat(matchingService.attemptMatch(requestAt(ORIGIN, "PREMIUM"))).isEmpty();
    }
}
