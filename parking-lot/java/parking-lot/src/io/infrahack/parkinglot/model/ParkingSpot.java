package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.SpotStatus;
import io.infrahack.parkinglot.enums.SpotType;

/**
 * A single bay. Occupancy and serviceability are separate dimensions: an
 * occupied spot can be taken out of service, but it must not be returned to the
 * free-list until the vehicle actually leaves. {@link #status()} is a derived
 * display value; {@link ParkingLevel}'s free-list remains the assignment source
 * of truth.
 */
public class ParkingSpot {
    private final String id;
    private final SpotType type;
    private final int level;
    private boolean occupied;
    private boolean outOfService;

    public ParkingSpot(String id, SpotType type, int level) {
        this.id = id;
        this.type = type;
        this.level = level;
    }

    public String id() {
        return id;
    }

    public SpotType type() {
        return type;
    }

    public int level() {
        return level;
    }

    public synchronized SpotStatus status() {
        if (outOfService) {
            return SpotStatus.OUT_OF_SERVICE;
        }
        return occupied ? SpotStatus.OCCUPIED : SpotStatus.FREE;
    }

    synchronized void markOccupied() {
        this.occupied = true;
    }

    synchronized void markFree() {
        this.occupied = false;
    }

    synchronized void markOutOfService() {
        this.outOfService = true;
    }

    synchronized boolean returnToService() {
        if (!outOfService) {
            return false;
        }
        this.outOfService = false;
        return true;
    }

    synchronized boolean isOutOfService() {
        return outOfService;
    }

    synchronized boolean isFreeForAssignment() {
        return !occupied && !outOfService;
    }

    @Override
    public String toString() {
        return "Spot[" + id + " " + type + " L" + level + " " + status() + "]";
    }
}
