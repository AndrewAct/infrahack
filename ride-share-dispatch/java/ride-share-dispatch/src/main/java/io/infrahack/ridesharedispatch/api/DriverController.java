package io.infrahack.ridesharedispatch.api;

import io.infrahack.ridesharedispatch.domain.Driver;
import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.infrastructure.redis.DriverOperationalStateStore;
import io.infrahack.ridesharedispatch.service.DriverService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Driver registration (durable, rare) and hot pings (availability + location, frequent).
 * No authentication -- see module README "Out of scope".
 */
@RestController
@RequestMapping("/drivers")
public class DriverController {

    public record RegisterDriverRequest(@NotBlank @Size(max = 120) String displayName,
                                       @NotBlank @Size(max = 40) String serviceType) {
    }

    public record DriverResponse(UUID driverId, String displayName, String serviceType, double rating,
                                 String accountStatus, Instant createdAt) {
        static DriverResponse from(Driver driver) {
            return new DriverResponse(driver.id().value(), driver.displayName(), driver.serviceType(),
                    driver.rating(), driver.accountStatus().name(), driver.createdAt());
        }
    }

    public record AvailabilityRequest(@NotNull Boolean available) {
    }

    public record LocationUpdateRequest(
                                         @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
                                         @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
                                         @PositiveOrZero long sequenceNumber,
                                         Instant clientTimestamp) {
    }

    public record LocationUpdateResponse(String result) {
    }

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @PostMapping
    public ResponseEntity<DriverResponse> register(@Valid @RequestBody RegisterDriverRequest request) {
        Driver driver = driverService.register(request.displayName(), request.serviceType());
        return ResponseEntity.status(HttpStatus.CREATED).body(DriverResponse.from(driver));
    }

    @PostMapping("/{driverId}/availability")
    public ResponseEntity<Void> setAvailability(@PathVariable UUID driverId,
                                                @Valid @RequestBody AvailabilityRequest request) {
        driverService.setAvailability(DriverId.of(driverId), request.available());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{driverId}/location")
    public ResponseEntity<LocationUpdateResponse> updateLocation(@PathVariable UUID driverId,
                                                                   @Valid @RequestBody LocationUpdateRequest request) {
        DriverOperationalStateStore.LocationUpdateResult result = driverService.recordLocation(
                DriverId.of(driverId), new GeoPoint(request.latitude(), request.longitude()),
                request.sequenceNumber(), request.clientTimestamp() == null ? Instant.now() : request.clientTimestamp());
        return ResponseEntity.ok(new LocationUpdateResponse(result.name()));
    }
}
