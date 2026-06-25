package io.infrahack.parkinglot.test;

import io.infrahack.parkinglot.enums.Role;
import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.enums.TicketStatus;
import io.infrahack.parkinglot.exception.NoAvailableSpotException;
import io.infrahack.parkinglot.exception.PaymentRequiredException;
import io.infrahack.parkinglot.exception.PermissionDeniedException;
import io.infrahack.parkinglot.exception.StaleObjectException;
import io.infrahack.parkinglot.exception.VehicleAlreadyParkedException;
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
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class ParkingServiceTest {

    private final User driver = new User("d1", "Driver", Set.of(Role.DRIVER));
    private final User admin = new User("a1", "Admin", Set.of(Role.ADMIN));

    private ParkingService newService(ParkingLot lot) {
        return new ParkingService(
                lot,
                new NearestFirstAssignmentStrategy(),
                HourlyPricingStrategy.withDefaults(),
                new InMemoryTicketRepository(),
                new PermissionService(),
                new MetricsCollector(),
                new AuditService(),
                Money.ofCents(5000));
    }

    private ParkingLot singleLevel(Map<SpotType, Integer> mix) {
        return new ParkingLotFactory().create("test", List.of(mix));
    }

    @Test
    public void electricCarPrefersChargerSpot() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1, SpotType.EV, 1));
        ParkingService service = newService(lot);

        ParkingTicket ticket = service.park(driver, new Car("EV-1", true));
        assertTrue(ticket.spotId().contains("EV"), "electric car should take the EV bay first");
    }

    @Test
    public void carFallsBackToLargeWhenCompactGone() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1, SpotType.LARGE, 1));
        ParkingService service = newService(lot);

        ParkingTicket first = service.park(driver, new Car("C1"));
        ParkingTicket second = service.park(driver, new Car("C2"));
        assertTrue(first.spotId().contains("COMPACT"));
        assertTrue(second.spotId().contains("LARGE"), "second car falls back to a large bay");
    }

    @Test
    public void truckCannotFitMotorcycleOrCompactBays() {
        ParkingLot lot = singleLevel(Map.of(SpotType.MOTORCYCLE, 2, SpotType.COMPACT, 2));
        ParkingService service = newService(lot);
        assertThrows(NoAvailableSpotException.class, () -> service.park(driver, new Truck("T1")));
    }

    @Test
    public void duplicatePlateIsRejected() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 2));
        ParkingService service = newService(lot);
        service.park(driver, new Car("DUP"));
        assertThrows(VehicleAlreadyParkedException.class, () -> service.park(driver, new Car("DUP")));
    }

    @Test
    public void fullLotRejectsThenReopensAfterExit() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1));
        ParkingService service = newService(lot);

        ParkingTicket t = service.park(driver, new Car("C1"));
        assertThrows(NoAvailableSpotException.class, () -> service.park(driver, new Car("C2")));

        Money fee = service.checkout(driver, t.id());
        service.pay(driver, t.id(), fee);
        service.exit(driver, t.id());

        // spot is free again
        ParkingTicket t2 = service.park(driver, new Car("C3"));
        assertEquals(t2.spotId(), t.spotId());
    }

    @Test
    public void cannotExitBeforePaying() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1));
        ParkingService service = newService(lot);
        ParkingTicket t = service.park(driver, new Car("C1"));
        service.checkout(driver, t.id());
        assertThrows(PaymentRequiredException.class, () -> service.exit(driver, t.id()));
    }

    @Test
    public void doubleExitIsRejected() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1));
        ParkingService service = newService(lot);
        ParkingTicket t = service.park(driver, new Car("C1"));
        Money fee = service.checkout(driver, t.id());
        service.pay(driver, t.id(), fee);
        service.exit(driver, t.id());
        // second exit on a closed ticket must fail (no second spot release)
        assertThrows(RuntimeException.class, () -> service.exit(driver, t.id()));
    }

    @Test
    public void nonDriverCannotPark() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1));
        ParkingService service = newService(lot);
        assertThrows(PermissionDeniedException.class, () -> service.park(admin, new Car("C1")));
    }

    @Test
    public void staleTicketSaveDoesNotMutateStoredTicket() {
        TicketRepository tickets = new InMemoryTicketRepository();
        ParkingTicket original = new ParkingTicket("t1", new Car("C1"), Instant.parse("2026-06-24T08:00:00Z"));
        tickets.save(original, original.version());

        ParkingTicket stale = tickets.findById("t1").orElseThrow();
        ParkingTicket winner = tickets.findById("t1").orElseThrow();
        winner.markLost();
        tickets.save(winner, winner.version());

        stale.startCheckout(Instant.parse("2026-06-24T09:00:00Z"), Money.ofCents(200));
        assertThrows(StaleObjectException.class, () -> tickets.save(stale, stale.version()));

        ParkingTicket stored = tickets.findById("t1").orElseThrow();
        assertEquals(stored.status(), TicketStatus.LOST);
        assertEquals(stored.fee(), Money.zero());
    }

    @Test
    public void returningOccupiedOutOfServiceSpotDoesNotMakeItAvailable() {
        ParkingLot lot = singleLevel(Map.of(SpotType.COMPACT, 1));
        ParkingService service = newService(lot);
        AdminService adminService = new AdminService(
                lot,
                new PermissionService(),
                new MetricsCollector(),
                new AuditService());

        ParkingTicket ticket = service.park(driver, new Car("C1"));
        assertTrue(adminService.takeSpotOutOfService(admin, ticket.level(), ticket.spotId()));
        assertTrue(adminService.returnSpotToService(admin, ticket.level(), ticket.spotId()));
        assertThrows(NoAvailableSpotException.class, () -> service.park(driver, new Car("C2")));

        Money fee = service.checkout(driver, ticket.id());
        service.pay(driver, ticket.id(), fee);
        service.exit(driver, ticket.id());

        ParkingTicket next = service.park(driver, new Car("C2"));
        assertEquals(next.spotId(), ticket.spotId());
    }
}
