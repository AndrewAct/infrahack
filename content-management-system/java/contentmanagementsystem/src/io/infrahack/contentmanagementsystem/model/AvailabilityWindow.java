package io.infrahack.contentmanagementsystem.model;

import java.time.Instant;

public record AvailabilityWindow(
        String region,
        Instant start,
        Instant end
) {
    boolean covers(String requestedRegion, Instant requestedTime) {
        return region.equals(requestedRegion) && requestedTime.isAfter(start) && requestedTime.isBefore(end);
    }
}
