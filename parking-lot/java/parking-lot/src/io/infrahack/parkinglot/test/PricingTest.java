package io.infrahack.parkinglot.test;

import io.infrahack.parkinglot.enums.VehicleType;
import io.infrahack.parkinglot.model.Car;
import io.infrahack.parkinglot.model.Money;
import io.infrahack.parkinglot.model.ParkingTicket;
import io.infrahack.parkinglot.model.Truck;
import io.infrahack.parkinglot.model.Vehicle;
import io.infrahack.parkinglot.strategy.HourlyPricingStrategy;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;

public class PricingTest {

    private final Instant entry = Instant.parse("2026-06-24T08:00:00Z");

    private ParkingTicket ticketFor(Vehicle vehicle) {
        return new ParkingTicket("t", vehicle, entry);
    }

    private HourlyPricingStrategy pricing() {
        Map<VehicleType, Long> rates = new EnumMap<>(VehicleType.class);
        rates.put(VehicleType.CAR, 200L);
        rates.put(VehicleType.TRUCK, 400L);
        rates.put(VehicleType.MOTORCYCLE, 100L);
        return new HourlyPricingStrategy(Duration.ofMinutes(15), rates, 2500L);
    }

    @Test
    public void withinGraceIsFree() {
        Money fee = pricing().computeFee(ticketFor(new Car("C")), entry.plus(Duration.ofMinutes(10)));
        assertEquals(fee, Money.zero());
    }

    @Test
    public void partialHourRoundsUp() {
        // 61 minutes -> 2 started hours -> 2 * $2.00
        Money fee = pricing().computeFee(ticketFor(new Car("C")), entry.plus(Duration.ofMinutes(61)));
        assertEquals(fee, Money.ofCents(400));
    }

    @Test
    public void rateVariesByVehicleType() {
        Instant exit = entry.plus(Duration.ofMinutes(90)); // 2 started hours
        assertEquals(pricing().computeFee(ticketFor(new Truck("T")), exit), Money.ofCents(800));
        assertEquals(pricing().computeFee(ticketFor(new Car("C")), exit), Money.ofCents(400));
    }

    @Test
    public void dailyCapApplies() {
        // 20 hours * $2 = $40 gross, capped at $25/day
        Money fee = pricing().computeFee(ticketFor(new Car("C")), entry.plus(Duration.ofHours(20)));
        assertEquals(fee, Money.ofCents(2500));
    }

    @Test
    public void multiDayCapStacks() {
        // 30 hours -> 2 days -> cap = 2 * $25 = $50; gross 30*$2 = $60 -> capped to $50
        Money fee = pricing().computeFee(ticketFor(new Car("C")), entry.plus(Duration.ofHours(30)));
        assertEquals(fee, Money.ofCents(5000));
    }
}
