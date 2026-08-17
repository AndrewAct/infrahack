package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Driver;
import io.infrahack.ridesharedispatch.domain.DriverOperationalState;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.infrastructure.redis.DriverOperationalStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #4: an out-of-order location update cannot overwrite a newer position.
 * See DriverOperationalStateStore's Lua compare-and-set for why a plain HGET+HSET would
 * not be safe here.
 */
class LocationOrderingTest extends AbstractIntegrationTest {

    @Autowired
    private DriverOperationalStateStore stateStore;

    @Test
    void olderSequenceNumberIsRejectedAndDoesNotOverwriteNewerPosition() {
        Driver driver = driverService.register("Driver", "STANDARD");
        driverService.setAvailability(driver.id(), true);

        var accepted = driverService.recordLocation(driver.id(), new GeoPoint(1.0, 1.0), 10L, Instant.now());
        assertThat(accepted).isEqualTo(DriverOperationalStateStore.LocationUpdateResult.ACCEPTED);

        var stale = driverService.recordLocation(driver.id(), new GeoPoint(2.0, 2.0), 5L, Instant.now());
        assertThat(stale).isEqualTo(DriverOperationalStateStore.LocationUpdateResult.REJECTED_STALE_SEQUENCE);

        var repeat = driverService.recordLocation(driver.id(), new GeoPoint(3.0, 3.0), 10L, Instant.now());
        assertThat(repeat).isEqualTo(DriverOperationalStateStore.LocationUpdateResult.REJECTED_STALE_SEQUENCE);

        Optional<DriverOperationalState> state = stateStore.get(driver.id());
        assertThat(state).isPresent();
        assertThat(state.get().location()).contains(new GeoPoint(1.0, 1.0));
        assertThat(state.get().sequenceNumber()).isEqualTo(10L);

        var newer = driverService.recordLocation(driver.id(), new GeoPoint(4.0, 4.0), 11L, Instant.now());
        assertThat(newer).isEqualTo(DriverOperationalStateStore.LocationUpdateResult.ACCEPTED);
        assertThat(stateStore.get(driver.id()).orElseThrow().location()).contains(new GeoPoint(4.0, 4.0));
    }
}
