package io.infrahack.elevator.model;

import io.infrahack.elevator.enums.ButtonType;
import io.infrahack.elevator.enums.Direction;
import io.infrahack.elevator.exceptions.ValidationException;
import io.infrahack.elevator.request.HallRequest;

public class HallPanel {
    private final int floor;
    private final Button upButton = new Button("UP", ButtonType.UP);
    private final Button downButton = new Button("DOWN", ButtonType.DOWN);

    public HallPanel(int floor) {
        this.floor = floor;
    }

    public HallRequest press(Direction direction) {
        if (direction == Direction.UP) {
            upButton.press();
            return new HallRequest(floor, Direction.UP);
        }
        if (direction == Direction.DOWN) {
            downButton.press();
            return new HallRequest(floor, Direction.DOWN);
        }
        throw new ValidationException("Invalid direction");
    }

    public void clear(Direction direction) {
        if (direction == Direction.UP) {
            upButton.clear();
        } else if (direction == Direction.DOWN) {
            downButton.clear();
        }
    }

    public int floor() {
        return floor;
    }
}
