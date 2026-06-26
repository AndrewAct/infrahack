// src/io/infrahack/elevator/Main.java
package io.infrahack.elevator;

import io.infrahack.elevator.enums.Direction;
import io.infrahack.elevator.model.Building;
import io.infrahack.elevator.model.Elevator;
import io.infrahack.elevator.model.FloorRange;
import io.infrahack.elevator.repository.BuildingRepository;
import io.infrahack.elevator.repository.InMemoryBuildingRepository;
import io.infrahack.elevator.service.AuditService;
import io.infrahack.elevator.service.ElevatorSystemService;
import io.infrahack.elevator.service.MetricsCollector;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        FloorRange floorRange = new FloorRange(1, 10);
        Elevator elevator = new Elevator("e1", 1, floorRange, 1000);
        Building building = new Building("b1", floorRange, List.of(elevator));

        BuildingRepository repository = new InMemoryBuildingRepository();
        repository.save(building);

        AuditService audit = new AuditService();
        MetricsCollector metrics = new MetricsCollector();

        ElevatorSystemService service =
                new ElevatorSystemService("b1", repository, audit, metrics);

        service.pressHallButton(4, Direction.UP);
        run(service, 4);

        service.pressFloorButton(8);
        run(service, 6);

        service.updateLoad(100);
        service.tick();
        print(service);

        service.updateLoad(100);
        service.pressCloseDoorButton();
        service.pressFloorButton(2);
        run(service, 10);

        service.pressEmergencyButton();
        print(service);

        System.out.println("Hall buttons=" + metrics.count("elevator.hall.button.pressed"));
        System.out.println("Floor buttons=" + metrics.count("elevator.floor.button.pressed"));
        System.out.println("Overloaded=" + metrics.count("elevator.overloaded"));
        System.out.println("Emergency=" + metrics.count("elevator.emergency"));
        System.out.println("Audit=" + audit.events());
    }

    private static void run(ElevatorSystemService service, int ticks) {
        for (int i = 0; i < ticks; i++) {
            service.tick();
            print(service);
        }
    }

    private static void print(ElevatorSystemService service) {
        Elevator elevator = service.currentElevator();
        System.out.println(
                "floor=" + elevator.currentFloor()
                        + ", direction=" + elevator.direction()
                        + ", status=" + elevator.status()
                        + ", door=" + elevator.doorState()
                        + ", load=" + elevator.currentLoadKg() + "/" + elevator.maxLoadKg()
                        + ", overloaded=" + elevator.isOverloaded()
                        + ", stops=" + elevator.stops()
        );
    }
}