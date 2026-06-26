package io.infrahack.elevator.service;

import io.infrahack.elevator.enums.Direction;
import io.infrahack.elevator.exceptions.ValidationException;
import io.infrahack.elevator.model.Building;
import io.infrahack.elevator.model.Elevator;
import io.infrahack.elevator.repository.BuildingRepository;
import io.infrahack.elevator.request.CarRequest;
import io.infrahack.elevator.request.HallRequest;

public class ElevatorSystemService {
    private final String buildingId;
    private final BuildingRepository repository;
    private final AuditService auditService;
    private final MetricsCollector metricsCollector;

    public ElevatorSystemService(
            String buildingId,
            BuildingRepository repository,
            AuditService auditService,
            MetricsCollector metricsCollector
    ) {
        this.buildingId = buildingId;
        this.repository = repository;
        this.auditService = auditService;
        this.metricsCollector = metricsCollector;
    }

    public void pressHallButton(int floor, Direction direction) {
        Building building = building();
        HallRequest request = building.hallPanel(floor).press(direction);

        building.defaultElevator().addRequest(request);

        repository.save(building);
        auditService.record("HALL_BUTTON_" + direction, buildingId);
        metricsCollector.increment("elevator.hall.button.pressed");
    }

    public void pressFloorButton(int floor) {
        Building building = building();
        Elevator elevator = building.defaultElevator();

        CarRequest request = elevator.carPanel().pressFloor(floor);
        elevator.addRequest(request);

        repository.save(building);
        auditService.record("FLOOR_BUTTON_PRESSED", buildingId);
        metricsCollector.increment("elevator.floor.button.pressed");
    }

    public void pressOpenDoorButton() {
        Building building = building();
        Elevator elevator = building.defaultElevator();

        elevator.carPanel().pressOpenDoor();
        elevator.openDoor();

        repository.save(building);
        auditService.record("OPEN_DOOR_BUTTON_PRESSED", buildingId);
    }

    public void pressCloseDoorButton() {
        Building building = building();
        Elevator elevator = building.defaultElevator();

        elevator.carPanel().pressCloseDoor();
        elevator.closeDoor();

        repository.save(building);
        auditService.record("CLOSE_DOOR_BUTTON_PRESSED", buildingId);
    }

    public void pressEmergencyButton() {
        Building building = building();
        building.defaultElevator().pressEmergency();

        repository.save(building);
        auditService.record("EMERGENCY_BUTTON_PRESSED", buildingId);
        metricsCollector.increment("elevator.emergency");
    }

    public void resetEmergency() {
        Building building = building();
        building.defaultElevator().resetEmergency();

        repository.save(building);
        auditService.record("EMERGENCY_RESET", buildingId);
    }

    public void updateLoad(int loadKg) {
        Building building = building();
        Elevator elevator = building.defaultElevator();

        elevator.updateLoad(loadKg);

        repository.save(building);
        auditService.record("LOAD_UPDATED", buildingId);

        if (elevator.isOverloaded()) {
            metricsCollector.increment("elevator.overloaded");
        }
    }

    // Simulate a step of the elevator system
    public void tick() {
        Building building = building();
        building.defaultElevator().tick();

        repository.save(building);
    }

    public Elevator currentElevator() {
        return building().defaultElevator();
    }

    private Building building() {
        return repository.findById(buildingId)
                .orElseThrow(() -> new ValidationException("Building not found"));
    }
}