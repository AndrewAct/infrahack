package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.enums.VehicleType;

import java.util.ArrayList;
import java.util.List;

/**
 * A vehicle seeking a spot. The subclass fixes the {@link VehicleType} (size
 * class); the {@code electric} flag is orthogonal and only changes spot
 * preference, not which subclass is used.
 */
public abstract class Vehicle {
    protected final String licensePlate;
    protected final boolean electric;

    protected Vehicle(String licensePlate, boolean electric) {
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("licensePlate is required");
        }
        this.licensePlate = licensePlate;
        this.electric = electric;
    }

    public abstract VehicleType type();

    /**
     * Spot footprints this vehicle will accept, best-fit first. Electric
     * vehicles try charger (EV) spots before falling back to their size class,
     * so chargers are reserved for the vehicles that can use them.
     */
    public List<SpotType> spotPreference() {
        List<SpotType> preference = new ArrayList<>();
        if (electric) {
            preference.add(SpotType.EV);
        }
        preference.addAll(type().baseSpotPreference());
        return preference;
    }

    public String licensePlate() {
        return licensePlate;
    }

    public boolean isElectric() {
        return electric;
    }
}
