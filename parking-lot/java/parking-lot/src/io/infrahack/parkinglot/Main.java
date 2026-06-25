package io.infrahack.parkinglot;

import io.infrahack.parkinglot.enums.Role;
import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.exception.NoAvailableSpotException;
import io.infrahack.parkinglot.factory.ParkingLotFactory;
import io.infrahack.parkinglot.model.Car;
import io.infrahack.parkinglot.model.Money;
import io.infrahack.parkinglot.model.Motorcycle;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingTicket;
import io.infrahack.parkinglot.model.Truck;
import io.infrahack.parkinglot.model.User;
import io.infrahack.parkinglot.repository.InMemoryTicketRepository;
import io.infrahack.parkinglot.repository.TicketRepository;
import io.infrahack.parkinglot.service.AdminService;
import io.infrahack.parkinglot.service.AuditService;
import io.infrahack.parkinglot.service.MetricsCollector;
import io.infrahack.parkinglot.service.ParkingService;
import io.infrahack.parkinglot.service.PermissionService;
import io.infrahack.parkinglot.strategy.HourlyPricingStrategy;
import io.infrahack.parkinglot.strategy.NearestFirstAssignmentStrategy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end walkthrough of the lot: entry/assignment, the pay-then-exit
 * barrier, lost-ticket handling, admin maintenance, lot-full rejection, and the
 * observability surface (metrics + audit). Demo console output is intentionally
 * direct; core services stay quiet and emit metrics/audit instead.
 */
public class Main {
    public static void main(String[] args) {
        // A small two-level garage: level 1 (nearest) is mostly compact + EV; level 2 has the large bays.
        ParkingLot lot = new ParkingLotFactory().create("Downtown Garage", List.of(
                Map.of(SpotType.MOTORCYCLE, 1, SpotType.COMPACT, 2, SpotType.EV, 1),
                Map.of(SpotType.LARGE, 1)
        ));

        TicketRepository tickets = new InMemoryTicketRepository();
        PermissionService permissions = new PermissionService();
        MetricsCollector metrics = new MetricsCollector();
        AuditService audit = new AuditService();

        ParkingService parking = new ParkingService(
                lot,
                new NearestFirstAssignmentStrategy(),
                HourlyPricingStrategy.withDefaults(),
                tickets, permissions, metrics, audit,
                Money.ofCents(5000)); // $50 flat lost-ticket penalty
        AdminService adminService = new AdminService(lot, permissions, metrics, audit);

        User driver = new User("d1", "Driver Dana", Set.of(Role.DRIVER));
        User admin = new User("a1", "Admin Alex", Set.of(Role.ADMIN));

        System.out.println("== Capacity ==");
        System.out.println(adminService.occupancy(admin));

        System.out.println("\n== Entries ==");
        ParkingTicket carTicket = parking.park(driver, new Car("CAR-1"));
        System.out.println("Car        -> " + carTicket.spotId());                       // compact, level 1
        ParkingTicket evTicket = parking.park(driver, new Car("EV-1", true));
        System.out.println("Electric   -> " + evTicket.spotId());                         // EV bay (charger)
        ParkingTicket motoTicket = parking.park(driver, new Motorcycle("MOTO-1"));
        System.out.println("Motorcycle -> " + motoTicket.spotId());                       // motorcycle bay
        ParkingTicket truckTicket = parking.park(driver, new Truck("TRUCK-1"));
        System.out.println("Truck      -> " + truckTicket.spotId());                       // large bay, level 2
        System.out.println(adminService.occupancy(admin));

        System.out.println("\n== Pay & exit (the car) ==");
        Money fee = parking.checkout(driver, carTicket.id());
        System.out.println("Fee due: " + fee + " (within grace -> free)");
        parking.pay(driver, carTicket.id(), fee);
        parking.exit(driver, carTicket.id());
        System.out.println("Car exited; freed " + carTicket.spotId());

        System.out.println("\n== Lost ticket (the motorcycle) ==");
        Money penalty = parking.reportLostTicket(driver, "MOTO-1");
        System.out.println("Lost-ticket penalty: " + penalty);
        parking.pay(driver, motoTicket.id(), penalty);
        parking.exit(driver, motoTicket.id());

        System.out.println("\n== Admin pulls the last compact bay for maintenance ==");
        adminService.takeSpotOutOfService(admin, 1, "L1-COMPACT-2");
        System.out.println(adminService.occupancy(admin));

        System.out.println("\n== Lot full -> rejection ==");
        try {
            parking.park(driver, new Truck("TRUCK-2")); // only large bay is taken
        } catch (NoAvailableSpotException e) {
            System.out.println("Rejected as expected: " + e.getMessage());
        }

        System.out.println("\n== Observability ==");
        System.out.println("entries=" + metrics.count("parking.entry")
                + " exits=" + metrics.count("parking.exit")
                + " rejected.full=" + metrics.count("parking.rejected.full")
                + " lost=" + metrics.count("parking.lost_ticket"));
        System.out.println("revenue=" + Money.ofCents(metrics.count("parking.revenue.cents")));
        System.out.println("occupied gauge=" + metrics.gauge("parking.occupied"));
        System.out.println("audit trail:");
        audit.events().forEach(e -> System.out.println("  " + e));
    }
}
