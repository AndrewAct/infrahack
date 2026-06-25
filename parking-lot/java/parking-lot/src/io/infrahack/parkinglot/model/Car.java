package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.VehicleType;

public class Car extends Vehicle {
    public Car(String licensePlate) {
        this(licensePlate, false);
    }

    public Car(String licensePlate, boolean electric) {
        super(licensePlate, electric);
    }

    @Override
    public VehicleType type() {
        return VehicleType.CAR;
    }
}
