package io.infrahack.parkinglot.strategy;

import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingSpot;
import io.infrahack.parkinglot.model.Vehicle;

import java.util.Optional;

/**
 * Decides which free spot a vehicle gets. Implementations MUST claim the spot
 * atomically (via {@code ParkingLevel#tryClaim}) and return the already-claimed
 * spot, so the decision and the reservation are one step and cannot race.
 */
public interface SpotAssignmentStrategy {
    Optional<ParkingSpot> assign(ParkingLot lot, Vehicle vehicle);
}
