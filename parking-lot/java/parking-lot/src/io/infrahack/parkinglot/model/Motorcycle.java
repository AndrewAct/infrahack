package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.VehicleType;

public class Motorcycle extends Vehicle {
    public Motorcycle(String licensePlate) {
        this(licensePlate, false);
    }

    public Motorcycle(String licensePlate, boolean electric) {
        super(licensePlate, electric);
    }

    @Override
    public VehicleType type() {
        return VehicleType.MOTORCYCLE;
    }
}
