package io.infrahack.elevator.request;

public class CarRequest implements ElevatorRequest {
    private final int destinationFloor;

    public CarRequest(int destinationFloor) {
        this.destinationFloor = destinationFloor;
    }

    @Override
    public int floor() {
        return destinationFloor;
    }
}
