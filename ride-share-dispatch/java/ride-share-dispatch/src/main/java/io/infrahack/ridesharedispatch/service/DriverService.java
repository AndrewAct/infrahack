package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.Driver;
import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.exception.NotFoundException;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.infrastructure.redis.DriverOperationalStateStore;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.DriverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Owns the split described in Driver's javadoc: registration writes the durable
 * PostgreSQL profile once; availability and location are high-frequency and go
 * straight to Redis, never touching Postgres on the hot path.
 */
@Service
public class DriverService {

    private static final Logger log = LoggerFactory.getLogger(DriverService.class);

    private final DriverRepository driverRepository;
    private final DriverOperationalStateStore stateStore;
    private final DispatchProperties properties;
    private final DispatchMetrics metrics;

    public DriverService(DriverRepository driverRepository, DriverOperationalStateStore stateStore,
                         DispatchProperties properties, DispatchMetrics metrics) {
        this.driverRepository = driverRepository;
        this.stateStore = stateStore;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Driver register(String displayName, String serviceType) {
        Driver driver = new Driver(DriverId.newId(), displayName, serviceType, 5.00,
                Driver.AccountStatus.ACTIVE, Instant.now());
        driverRepository.insert(driver);
        return driver;
    }

    public Driver requireDriver(DriverId driverId) {
        return driverRepository.findById(driverId)
                .orElseThrow(() -> new NotFoundException("Driver", driverId));
    }

    public void setAvailability(DriverId driverId, boolean available) {
        Driver driver = requireDriver(driverId);
        Duration freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());
        boolean changed = available
                ? stateStore.setAvailable(driver, freshnessWindow)
                : stateStore.setOffline(driver, freshnessWindow);
        if (!changed) {
            throw new ConflictException("Driver %s is occupied and cannot change availability".formatted(driverId));
        }
    }

    public DriverOperationalStateStore.LocationUpdateResult recordLocation(
            DriverId driverId, GeoPoint point, long sequenceNumber, Instant clientTimestamp) {
        requireDriver(driverId);
        Instant serverReceiveTime = Instant.now();
        Duration freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());

        var result = metrics.locationUpdateLatency().record(() ->
                stateStore.recordLocation(driverId, point, sequenceNumber, serverReceiveTime, freshnessWindow));

        metrics.locationUpdatesTotal().increment();
        if (result == DriverOperationalStateStore.LocationUpdateResult.REJECTED_STALE_SEQUENCE) {
            metrics.locationUpdatesStaleTotal().increment();
            log.info("stale location rejected driverId={} sequenceNumber={} clientTimestamp={}",
                    driverId, sequenceNumber, clientTimestamp);
        }
        return result;
    }
}
