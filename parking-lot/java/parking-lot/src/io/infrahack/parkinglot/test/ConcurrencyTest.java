package io.infrahack.parkinglot.test;

import io.infrahack.parkinglot.enums.Role;
import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.exception.NoAvailableSpotException;
import io.infrahack.parkinglot.factory.ParkingLotFactory;
import io.infrahack.parkinglot.model.Car;
import io.infrahack.parkinglot.model.Money;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingTicket;
import io.infrahack.parkinglot.model.User;
import io.infrahack.parkinglot.repository.InMemoryTicketRepository;
import io.infrahack.parkinglot.service.AuditService;
import io.infrahack.parkinglot.service.MetricsCollector;
import io.infrahack.parkinglot.service.ParkingService;
import io.infrahack.parkinglot.service.PermissionService;
import io.infrahack.parkinglot.strategy.HourlyPricingStrategy;
import io.infrahack.parkinglot.strategy.NearestFirstAssignmentStrategy;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ConcurrencyTest {

    private final User driver = new User("d1", "Driver", Set.of(Role.DRIVER));

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

    /**
     * The headline invariant: when more drivers than spots race for entry, the
     * lot hands out each spot to exactly one driver — never zero, never two.
     */
    @Test
    public void concurrentEntriesNeverDoubleAssignASpot() throws InterruptedException {
        int spots = 50;
        int drivers = 200;
        ParkingLot lot = new ParkingLotFactory().create("stress", List.of(Map.of(SpotType.COMPACT, spots)));
        ParkingService service = newService(lot);

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(drivers);
        ConcurrentLinkedQueue<String> claimedSpots = new ConcurrentLinkedQueue<>();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < drivers; i++) {
            String plate = "CAR-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ParkingTicket ticket = service.park(driver, new Car(plate));
                    claimedSpots.add(ticket.spotId());
                } catch (NoAvailableSpotException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(claimedSpots.size(), spots, "exactly all spots filled");
        assertEquals(rejected.get(), drivers - spots, "the rest are cleanly rejected");
        assertEquals(Set.copyOf(claimedSpots).size(), spots, "no spot assigned to two drivers");
        assertEquals(lot.occupancy().freeSpots(), 0, "lot reports full");
    }

    /**
     * Two terminals fire exit on the same paid ticket at once. The CAS on the
     * ticket version must let exactly one win, so the spot is freed once and can
     * be reused once — not twice.
     */
    @Test
    public void concurrentExitFreesSpotExactlyOnce() throws InterruptedException {
        ParkingLot lot = new ParkingLotFactory().create("exit", List.of(Map.of(SpotType.COMPACT, 1)));
        ParkingService service = newService(lot);

        ParkingTicket ticket = service.park(driver, new Car("C1"));
        Money fee = service.checkout(driver, ticket.id());
        service.pay(driver, ticket.id(), fee);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.exit(driver, ticket.id());
                    succeeded.incrementAndGet();
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(succeeded.get(), 1, "exactly one exit wins");
        assertEquals(failed.get(), 1, "the other is rejected");
        assertEquals(lot.occupancy().freeSpots(), 1, "spot freed exactly once");

        // and the freed spot is reusable exactly once
        service.park(driver, new Car("C2"));
        assertEquals(lot.occupancy().freeSpots(), 0);
        assertThrows(service);
    }

    private static void assertThrows(ParkingService service) {
        boolean threw = false;
        try {
            service.park(new User("d1", "Driver", Set.of(Role.DRIVER)), new Car("C3"));
        } catch (NoAvailableSpotException e) {
            threw = true;
        }
        assertTrue(threw, "no phantom second spot exists");
    }
}
