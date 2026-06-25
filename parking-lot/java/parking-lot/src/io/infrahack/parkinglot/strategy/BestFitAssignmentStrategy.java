package io.infrahack.parkinglot.strategy;

import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.model.ParkingLevel;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingSpot;
import io.infrahack.parkinglot.model.Vehicle;

import java.util.Optional;

/**
 * Maximize space utilization across the whole lot: exhaust the smallest
 * acceptable spot type on every level before considering a larger one, so a car
 * never burns a LARGE bay while a COMPACT sits empty two floors up. Spot-type
 * outer, level inner — the inverse loop nesting of {@link NearestFirstAssignmentStrategy},
 * which is exactly why both exist behind one interface.
 */
public class BestFitAssignmentStrategy implements SpotAssignmentStrategy {
    @Override
    public Optional<ParkingSpot> assign(ParkingLot lot, Vehicle vehicle) {
        for (SpotType type : vehicle.spotPreference()) {
            for (ParkingLevel level : lot.levels()) {
                Optional<ParkingSpot> spot = level.tryClaim(type);
                if (spot.isPresent()) {
                    return spot;
                }
            }
        }
        return Optional.empty();
    }
}
