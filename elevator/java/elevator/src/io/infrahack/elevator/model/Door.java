package io.infrahack.elevator.model;

import io.infrahack.elevator.enums.DoorState;

public class Door {
    private DoorState state = DoorState.CLOSED;

    public void open() {
        state = DoorState.OPEN;
    }
    public void close() {
        state = DoorState.CLOSED;
    }
    public boolean isOpen() {
        return state == DoorState.OPEN;
    }
    public DoorState state() {
        return state;
    }
}
