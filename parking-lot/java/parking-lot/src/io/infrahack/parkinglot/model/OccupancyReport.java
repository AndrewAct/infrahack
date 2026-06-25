package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.SpotType;

import java.util.Map;

/** Point-in-time occupancy snapshot, suitable for a dashboard gauge or admin view. */
public record OccupancyReport(int totalSpots,
                              long freeSpots,
                              Map<SpotType, Long> freeByType,
                              Map<Integer, Long> freeByLevel) {

    public long occupiedSpots() {
        return totalSpots - freeSpots;
    }

    public double occupancyRate() {
        return totalSpots == 0 ? 0.0 : (double) occupiedSpots() / totalSpots;
    }

    @Override
    public String toString() {
        return String.format("Occupancy[%d/%d used, %.0f%%, freeByType=%s]",
                occupiedSpots(), totalSpots, occupancyRate() * 100, freeByType);
    }
}
