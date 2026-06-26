package io.infrahack.elevator.model;

import io.infrahack.elevator.enums.Direction;
import io.infrahack.elevator.enums.DoorState;
import io.infrahack.elevator.enums.ElevatorStatus;
import io.infrahack.elevator.exceptions.ValidationException;
import io.infrahack.elevator.request.ElevatorRequest;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public class Elevator {
    private final String id;
    private final FloorRange floorRange;
    private final Door door = new Door();
    private final CarPanel carPanel;
    private final TreeSet<Integer> stops = new TreeSet<>();
    private final int maxLoadKg;

    private int currentLoadKg;
    private int currentFloor;
    private Direction direction = Direction.IDLE;
    private ElevatorStatus status = ElevatorStatus.IDLE;

    public Elevator(String id, int currentFloor, FloorRange floorRange, int maxLoadKg) {
        if (maxLoadKg <= 0) {
            throw new ValidationException("Max load must be greater than 0");
        }
        this.id = id;
        this.currentFloor = currentFloor;
        this.floorRange = floorRange;
        this.maxLoadKg = maxLoadKg;
        this.carPanel = new CarPanel(floorRange);

        floorRange.validate(currentFloor);
    }

    public void addRequest(ElevatorRequest request) {
        if (request.floor() == currentFloor) {
            return;
        }
        floorRange.validate(request.floor());
        if (status == ElevatorStatus.EMERGENCY) {
            throw new ValidationException("Elevator is in emergency");
        }
        stops.add(request.floor());
    }

    public void updateLoad(int loadKg) {
        if (loadKg < 0) {
            throw new ValidationException("Load cannot be negative");
        }
        currentLoadKg = loadKg;
        if (isOverloaded()) {
            door.open();
            direction = Direction.IDLE;
            status = ElevatorStatus.IDLE;
        }
    }

    public void openDoor() {
        if (status == ElevatorStatus.MOVING) {
            throw new ValidationException("Elevator is moving");
        }
        door.open();
    }

    public void closeDoor() {
        if (isOverloaded()) {
            throw new ValidationException("Elevator is overloaded");
        }
        door.close();
    }

    public void pressEmergency() {
        carPanel.pressEmergency();
        stops.clear();
        direction = Direction.IDLE;
        status = ElevatorStatus.EMERGENCY;
        door.open();
    }

    public void resetEmergency() {
        if (status != ElevatorStatus.EMERGENCY) {
            return;
        }
        carPanel.emergencyButton().clear();
        door.close();
        direction = Direction.IDLE;
        status = ElevatorStatus.IDLE;
    }

    public void tick() {
        if (status == ElevatorStatus.EMERGENCY) {
            return;
        }
        if (isOverloaded()) {
            door.open();
            direction = Direction.IDLE;
            status = ElevatorStatus.IDLE;
            return;
        }
        if (door.isOpen()) {
            door.close();
            return;
        }
        if (stops.isEmpty()) {
            direction = Direction.IDLE;
            status = ElevatorStatus.IDLE;
            return;
        }
        if (stops.contains(currentFloor)) {
            arrive();
            return;
        }
        int nextStop = chooseNextStop();
        moveOneFloorTowards(nextStop);
        if (stops.contains(currentFloor)) {
            arrive();
        }
    }

    private int chooseNextStop() {
        if (direction == Direction.UP) {
            Integer next = stops.ceiling(currentFloor);
            return next != null ? next : stops.last();
        }
        if (direction == Direction.DOWN) {
            Integer next = stops.floor(currentFloor);
            return next != null ? next : stops.first();
        }
        Integer up = stops.ceiling(currentFloor);
        Integer down = stops.floor(currentFloor);
        if (up == null) return down;
        if (down == null) return up;
        return Math.abs(up - currentFloor) <= Math.abs(currentFloor - down) ? up : down;
    }

    public void moveOneFloorTowards(int floor) {
        if (door.isOpen()) {
            throw new ValidationException("Elevator is open");
        }
        if (isOverloaded()) {
            throw new ValidationException("Elevator is overloaded");
        }
        status = ElevatorStatus.MOVING;
        if (floor > currentFloor) {
            currentFloor++;
            direction = Direction.UP;
        } else if (floor < currentFloor) {
            currentFloor--;
            direction = Direction.DOWN;
        }
    }

    public void pressStop() {
        if (status == ElevatorStatus.MOVING) {
            throw new ValidationException("Elevator is moving");
        }
    }

    public void updateStatus(ElevatorStatus status) {
        this.status = status;
    }

    public boolean isOverloaded() {
        return currentLoadKg >= maxLoadKg;
    }

    public String id() {
        return id;
    }

    public int currentFloor() {
        return currentFloor;
    }

    public Direction direction() {
        return direction;
    }

    public ElevatorStatus status() {
        return status;
    }

    public DoorState doorState() {
        return door.state();
    }

    public int currentLoadKg() {
        return currentLoadKg;
    }

    public int maxLoadKg() {
        return maxLoadKg;
    }

    public CarPanel carPanel() {
        return carPanel;
    }

    public Set<Integer> stops() {
        return Collections.unmodifiableSet(stops);
    }

    public boolean isIdle() {
        return status == ElevatorStatus.IDLE;
    }

    public boolean isMoving() {
        return status == ElevatorStatus.MOVING;
    }

    private void arrive() {
        stops.remove(currentFloor);
        carPanel.clearFloor(currentFloor);
        door.open();
        if (stops.isEmpty()) {
            direction = Direction.IDLE;
            status = ElevatorStatus.IDLE;
        }
    }
}
