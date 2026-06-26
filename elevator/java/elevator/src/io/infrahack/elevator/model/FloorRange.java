package io.infrahack.elevator.model;

import io.infrahack.elevator.exceptions.ValidationException;

public class FloorRange {
    private final int minFloor;
    private final int maxFloor;

    public FloorRange(int minFloor, int maxFloor) {
        if (minFloor > maxFloor) {
            throw new ValidationException("Min floor cannot be greater than max floor");
        }
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
    }

    public void validate(int floor) {
        if (floor < minFloor || floor > maxFloor) {
            throw new ValidationException("Floor " + floor + " is not in range " + minFloor + " to " + maxFloor);
        }
    }

    public int minFloor() {
        return minFloor;
    }

    public int maxFloor() {
        return maxFloor;
    }
}
