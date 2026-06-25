package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.VehicleType;

public class Truck extends Vehicle {
    public Truck(String licensePlate) {
        this(licensePlate, false);
    }

    public Truck(String licensePlate, boolean electric) {
        super(licensePlate, electric);
    }

    @Override
    public VehicleType type() {
        return VehicleType.TRUCK;
    }
}
