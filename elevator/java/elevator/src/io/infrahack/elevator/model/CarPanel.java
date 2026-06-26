package io.infrahack.elevator.model;

import io.infrahack.elevator.enums.ButtonType;
import io.infrahack.elevator.exceptions.ValidationException;
import io.infrahack.elevator.request.CarRequest;

import java.util.HashMap;
import java.util.Map;

public class CarPanel {
    private final Map<Integer, Button> floorButtons = new HashMap<>();
    private final Button openDoorButton = new Button("OPEN", ButtonType.OPEN_DOOR);
    private final Button closeDoorButton = new Button("CLOSE", ButtonType.CLOSE_DOOR);
    private final Button emergencyButton = new Button("EMERGENCY", ButtonType.EMERGENCY);

    public CarPanel(FloorRange floorRange) {
        for (int floor = floorRange.minFloor(); floor <= floorRange.maxFloor(); floor++) {
            floorButtons.put(floor, new Button(String.valueOf(floor), ButtonType.FLOOR));
        }
    }

    public CarRequest pressFloor(int floor) {
        Button button = floorButtons.get(floor);
        if (button == null) {
            throw new ValidationException("Invalid floor button " + floor);
        }
        button.press();
        return new CarRequest(floor);
    }

    public void pressOpenDoor() {
        openDoorButton.press();
    }

    public void pressCloseDoor() {
        closeDoorButton.press();
    }

    public void pressEmergency() {
        emergencyButton.press();
    }

    public void clearFloor(int floor) {
        Button button = floorButtons.get(floor);
        if (button != null) {
            button.clear();
        }
    }

    public Button openDoorButton() {
        return openDoorButton;
    }
    public Button closeDoorButton() {
        return closeDoorButton;
    }

    public Button emergencyButton() {
        return emergencyButton;
    }
}
