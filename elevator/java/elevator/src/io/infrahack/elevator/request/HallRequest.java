package io.infrahack.elevator.request;

import io.infrahack.elevator.enums.Direction;

public class HallRequest implements ElevatorRequest{
    private final int floor;
    private final Direction direction;

    public HallRequest(int floor, Direction direction) {
        if (direction == Direction.IDLE) {
            throw new IllegalArgumentException("Direction cannot be IDLE");
        }
        this.floor = floor;
        this.direction = direction;
    }
    @Override
    public int floor() {
        return floor;
    }

    public Direction direction() {
        return this.direction;
    }
}
