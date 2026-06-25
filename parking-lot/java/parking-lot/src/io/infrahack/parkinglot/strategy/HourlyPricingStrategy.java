package io.infrahack.parkinglot.strategy;

import io.infrahack.parkinglot.enums.VehicleType;
import io.infrahack.parkinglot.model.Money;
import io.infrahack.parkinglot.model.ParkingTicket;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Tiered hourly pricing:
 *   - a free grace period (default 15 min) so quick pick-ups/drop-offs pay nothing,
 *   - a per-{@link VehicleType} hourly rate, billed by the started hour (round up),
 *   - a per-24h cap so a long stay can't run away.
 *
 * All arithmetic is in integer cents. Hours are rounded UP because partial-hour
 * billing is the near-universal garage convention and it makes the fee a
 * deterministic function of (entry, exit) — easy to reproduce in a dispute.
 */
public class HourlyPricingStrategy implements PricingStrategy {
    private final Duration gracePeriod;
    private final Map<VehicleType, Long> hourlyRateCents;
    private final long dailyCapCents;

    public HourlyPricingStrategy(Duration gracePeriod,
                                 Map<VehicleType, Long> hourlyRateCents,
                                 long dailyCapCents) {
        this.gracePeriod = gracePeriod;
        this.hourlyRateCents = new EnumMap<>(hourlyRateCents);
        this.dailyCapCents = dailyCapCents;
    }

    /** Sensible defaults: 15-min grace, $1/$2/$4 per hour, $25/day cap. */
    public static HourlyPricingStrategy withDefaults() {
        Map<VehicleType, Long> rates = new EnumMap<>(VehicleType.class);
        rates.put(VehicleType.MOTORCYCLE, 100L);
        rates.put(VehicleType.CAR, 200L);
        rates.put(VehicleType.TRUCK, 400L);
        return new HourlyPricingStrategy(Duration.ofMinutes(15), rates, 2500L);
    }

    @Override
    public Money computeFee(ParkingTicket ticket, Instant exitTime) {
        Duration stay = Duration.between(ticket.entryTime(), exitTime);
        if (stay.isNegative() || stay.compareTo(gracePeriod) <= 0) {
            return Money.zero();
        }

        long rate = hourlyRateCents.getOrDefault(ticket.vehicle().type(), 200L);
        long billedHours = ceilDiv(stay.toMinutes(), 60);
        long gross = billedHours * rate;

        long days = ceilDiv(billedHours, 24);
        long cap = days * dailyCapCents;
        return Money.ofCents(Math.min(gross, cap));
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
