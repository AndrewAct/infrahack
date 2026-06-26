package io.infrahack.elevator.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Building {
    private final String id;
    private final FloorRange floorRange;
    private final List<Elevator> elevators;
    private final Map<Integer, HallPanel> hallPanels = new HashMap<>();

    public Building(String id, FloorRange floorRange, List<Elevator> elevators) {
        this.id = id;
        this.floorRange = floorRange;
        this.elevators = elevators;
        for (int floor = floorRange.minFloor(); floor <= floorRange.maxFloor(); floor++) {
            hallPanels.put(floor, new HallPanel(floor));
        }
    }

    public HallPanel hallPanel(int floor) {
        return hallPanels.get(floor);
    }

    public Elevator defaultElevator() {
        return elevators.get(0);
    }
    public String id() {
        return id;
    }
    public FloorRange floorRange() {
        return floorRange;
    }
    public List<Elevator> elevators() {
        return elevators;
    }
}
