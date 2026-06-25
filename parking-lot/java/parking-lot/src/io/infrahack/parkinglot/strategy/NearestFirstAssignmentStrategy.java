package io.infrahack.parkinglot.strategy;

import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.model.ParkingLevel;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingSpot;
import io.infrahack.parkinglot.model.Vehicle;

import java.util.Optional;

/**
 * Minimize walking distance: fill the nearest level first, and within a level
 * take the smallest acceptable spot (best-fit). Level outer, spot-type inner.
 * Good default for driver experience in a multi-level garage.
 */
public class NearestFirstAssignmentStrategy implements SpotAssignmentStrategy {
    @Override
    public Optional<ParkingSpot> assign(ParkingLot lot, Vehicle vehicle) {
        for (ParkingLevel level : lot.levels()) {
            for (SpotType type : vehicle.spotPreference()) {
                Optional<ParkingSpot> spot = level.tryClaim(type);
                if (spot.isPresent()) {
                    return spot;
                }
            }
        }
        return Optional.empty();
    }
}
