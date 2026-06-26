package io.infrahack.elevator.test;

import io.infrahack.elevator.enums.Direction;
import io.infrahack.elevator.enums.DoorState;
import io.infrahack.elevator.enums.ElevatorStatus;
import io.infrahack.elevator.exceptions.ValidationException;
import io.infrahack.elevator.model.Building;
import io.infrahack.elevator.model.Elevator;
import io.infrahack.elevator.model.FloorRange;
import io.infrahack.elevator.repository.BuildingRepository;
import io.infrahack.elevator.repository.InMemoryBuildingRepository;
import io.infrahack.elevator.service.AuditService;
import io.infrahack.elevator.service.ElevatorSystemService;
import io.infrahack.elevator.service.MetricsCollector;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

public class ElevatorSystemServiceTest {
    @Test
    void handlesHallButtonAndFloorButton() {
        TestContext ctx = context();

        ctx.service.pressHallButton(3, Direction.UP);
        ctx.service.tick();
        ctx.service.tick();

        assertEquals(3, ctx.service.currentElevator().currentFloor());
        assertEquals(DoorState.OPEN, ctx.service.currentElevator().doorState());

        ctx.service.pressFloorButton(6);
        ctx.service.tick();
        assertEquals(DoorState.CLOSED, ctx.service.currentElevator().doorState());

        ctx.service.tick();
        ctx.service.tick();
        ctx.service.tick();

        assertEquals(6, ctx.service.currentElevator().currentFloor());
        assertEquals(DoorState.OPEN, ctx.service.currentElevator().doorState());
        assertEquals(1, ctx.metrics.count("elevator.hall.button.pressed"));
        assertEquals(1, ctx.metrics.count("elevator.floor.button.pressed"));
    }

    @Test
    void openAndCloseDoorButtonsControlDoor() {
        TestContext ctx = context();

        ctx.service.pressOpenDoorButton();
        assertEquals(DoorState.OPEN, ctx.service.currentElevator().doorState());

        ctx.service.pressCloseDoorButton();
        assertEquals(DoorState.CLOSED, ctx.service.currentElevator().doorState());
    }

    @Test
    void overloadedElevatorKeepsDoorOpenAndDoesNotMove() {
        TestContext ctx = context();

        ctx.service.pressHallButton(5, Direction.UP);
        ctx.service.updateLoad(1200);
        ctx.service.tick();

        Elevator elevator = ctx.service.currentElevator();

        assertEquals(1, elevator.currentFloor());
        assertEquals(DoorState.OPEN, elevator.doorState());
        assertTrue(elevator.isOverloaded());
        assertEquals(1, ctx.metrics.count("elevator.overloaded"));
    }

    @Test
    void elevatorMovesAgainAfterOverloadIsFixed() {
        TestContext ctx = context();

        ctx.service.pressHallButton(3, Direction.UP);
        ctx.service.updateLoad(1200);
        ctx.service.tick();

        assertEquals(1, ctx.service.currentElevator().currentFloor());

        ctx.service.updateLoad(700);
        ctx.service.pressCloseDoorButton();
        ctx.service.tick();
        ctx.service.tick();

        assertEquals(3, ctx.service.currentElevator().currentFloor());
        assertEquals(DoorState.OPEN, ctx.service.currentElevator().doorState());
    }

    @Test
    void emergencyButtonClearsStopsAndOpensDoor() {
        TestContext ctx = context();

        ctx.service.pressHallButton(8, Direction.UP);
        ctx.service.tick();
        ctx.service.tick();

        ctx.service.pressEmergencyButton();

        Elevator elevator = ctx.service.currentElevator();

        assertEquals(ElevatorStatus.EMERGENCY, elevator.status());
        assertEquals(DoorState.OPEN, elevator.doorState());
        assertTrue(elevator.stops().isEmpty());
        assertTrue(elevator.carPanel().emergencyButton().isLit());
        assertEquals(1, ctx.metrics.count("elevator.emergency"));
    }

    @Test(expectedExceptions = ValidationException.class)
    void cannotCloseDoorWhenOverloaded() {
        TestContext ctx = context();

        ctx.service.updateLoad(1200);
        ctx.service.pressCloseDoorButton();
    }

    @Test(expectedExceptions = ValidationException.class)
    void cannotPressInvalidHallButton() {
        TestContext ctx = context();

        ctx.service.pressHallButton(3, Direction.IDLE);
    }

    @Test(expectedExceptions = ValidationException.class)
    void cannotPressInvalidFloorButton() {
        TestContext ctx = context();

        ctx.service.pressFloorButton(99);
    }

    private TestContext context() {
        FloorRange floorRange = new FloorRange(1, 10);
        Elevator elevator = new Elevator("e1", 1, floorRange, 1000);
        Building building = new Building("b1", floorRange, List.of(elevator));

        BuildingRepository repository = new InMemoryBuildingRepository();
        repository.save(building);

        AuditService audit = new AuditService();
        MetricsCollector metrics = new MetricsCollector();
        ElevatorSystemService service =
                new ElevatorSystemService("b1", repository, audit, metrics);

        return new TestContext(service, metrics);
    }

    private record TestContext(ElevatorSystemService service, MetricsCollector metrics) {}
}