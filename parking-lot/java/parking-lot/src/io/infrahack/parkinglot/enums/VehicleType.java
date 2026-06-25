package io.infrahack.parkinglot.enums;

import java.util.List;

/**
 * Vehicle classes and the spot footprints each can occupy, in best-fit order
 * (smallest acceptable spot first to minimize wasted space). The electric
 * preference for EV-charger spots is layered on per-vehicle, not here, because
 * "electric" is orthogonal to size.
 */
public enum VehicleType {
    MOTORCYCLE(List.of(SpotType.MOTORCYCLE, SpotType.COMPACT, SpotType.LARGE)),
    CAR(List.of(SpotType.COMPACT, SpotType.LARGE)),
    TRUCK(List.of(SpotType.LARGE));

    private final List<SpotType> baseSpotPreference;

    VehicleType(List<SpotType> baseSpotPreference) {
        this.baseSpotPreference = baseSpotPreference;
    }

    public List<SpotType> baseSpotPreference() {
        return baseSpotPreference;
    }
}
