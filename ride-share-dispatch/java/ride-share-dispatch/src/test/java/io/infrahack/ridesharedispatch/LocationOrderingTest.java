package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Agent;
import io.infrahack.ridesharedispatch.domain.AgentOperationalState;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #4: an out-of-order location update cannot overwrite a newer position.
 * See AgentOperationalStateStore's Lua compare-and-set for why a plain HGET+HSET would
 * not be safe here.
 */
class LocationOrderingTest extends AbstractIntegrationTest {

    @Autowired
    private AgentOperationalStateStore stateStore;

    @Test
    void olderSequenceNumberIsRejectedAndDoesNotOverwriteNewerPosition() {
        Agent agent = agentService.register("Driver", "STANDARD");
        agentService.setAvailability(agent.id(), true);

        var accepted = agentService.recordLocation(agent.id(), new GeoPoint(1.0, 1.0), 10L, Instant.now());
        assertThat(accepted).isEqualTo(AgentOperationalStateStore.LocationUpdateResult.ACCEPTED);

        var stale = agentService.recordLocation(agent.id(), new GeoPoint(2.0, 2.0), 5L, Instant.now());
        assertThat(stale).isEqualTo(AgentOperationalStateStore.LocationUpdateResult.REJECTED_STALE_SEQUENCE);

        var repeat = agentService.recordLocation(agent.id(), new GeoPoint(3.0, 3.0), 10L, Instant.now());
        assertThat(repeat).isEqualTo(AgentOperationalStateStore.LocationUpdateResult.REJECTED_STALE_SEQUENCE);

        Optional<AgentOperationalState> state = stateStore.get(agent.id());
        assertThat(state).isPresent();
        assertThat(state.get().location()).contains(new GeoPoint(1.0, 1.0));
        assertThat(state.get().sequenceNumber()).isEqualTo(10L);

        var newer = agentService.recordLocation(agent.id(), new GeoPoint(4.0, 4.0), 11L, Instant.now());
        assertThat(newer).isEqualTo(AgentOperationalStateStore.LocationUpdateResult.ACCEPTED);
        assertThat(stateStore.get(agent.id()).orElseThrow().location()).contains(new GeoPoint(4.0, 4.0));
    }
}
