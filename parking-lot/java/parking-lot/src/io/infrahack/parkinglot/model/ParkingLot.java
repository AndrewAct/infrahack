package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.strategy.SpotAssignmentStrategy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregate root for the physical structure: levels and gates. It owns spot
 * allocation but delegates the "which spot" decision to a pluggable
 * {@link SpotAssignmentStrategy}, keeping the lot agnostic to policy.
 */
public class ParkingLot {
    private final String name;
    private final List<ParkingLevel> levels;
    private final List<Gate> gates;
    private final Map<Integer, ParkingLevel> levelByNumber = new HashMap<>();

    public ParkingLot(String name, List<ParkingLevel> levels, List<Gate> gates) {
        this.name = name;
        this.levels = List.copyOf(levels);
        this.gates = List.copyOf(gates);
        for (ParkingLevel level : this.levels) {
            levelByNumber.put(level.number(), level);
        }
    }

    public Optional<ParkingSpot> claimSpot(Vehicle vehicle, SpotAssignmentStrategy strategy) {
        return strategy.assign(this, vehicle);
    }

    public void releaseSpot(ParkingSpot spot) {
        ParkingLevel level = levelByNumber.get(spot.level());
        if (level != null) {
            level.release(spot);
        }
    }

    public Optional<ParkingSpot> findSpot(int level, String spotId) {
        ParkingLevel parkingLevel = levelByNumber.get(level);
        return parkingLevel == null ? Optional.empty() : parkingLevel.findSpot(spotId);
    }

    public boolean takeSpotOutOfService(int level, String spotId) {
        ParkingLevel parkingLevel = levelByNumber.get(level);
        return parkingLevel != null && parkingLevel.takeOutOfService(spotId);
    }

    public boolean returnSpotToService(int level, String spotId) {
        ParkingLevel parkingLevel = levelByNumber.get(level);
        return parkingLevel != null && parkingLevel.returnToService(spotId);
    }

    public OccupancyReport occupancy() {
        int total = 0;
        long free = 0;
        Map<SpotType, Long> freeByType = new EnumMap<>(SpotType.class);
        Map<Integer, Long> freeByLevel = new HashMap<>();
        for (ParkingLevel level : levels) {
            total += level.totalSpots();
            free += level.freeCount();
            freeByLevel.put(level.number(), level.freeCount());
            for (SpotType type : SpotType.values()) {
                freeByType.merge(type, level.freeCount(type), Long::sum);
            }
        }
        return new OccupancyReport(total, free, freeByType, freeByLevel);
    }

    public String name() {
        return name;
    }

    public List<ParkingLevel> levels() {
        return levels;
    }

    public List<Gate> gates() {
        return new ArrayList<>(gates);
    }
}
