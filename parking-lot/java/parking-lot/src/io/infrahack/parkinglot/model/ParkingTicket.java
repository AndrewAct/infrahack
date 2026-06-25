package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.TicketStatus;
import io.infrahack.parkinglot.exception.InvalidTicketStateException;

import java.time.Instant;

/**
 * The contract between a vehicle and the lot for one stay. Holds the entry/exit
 * timestamps used for billing and enforces a small state machine so illegal
 * transitions (exit-before-pay, pay-twice) fail loudly instead of silently
 * corrupting revenue or freeing a spot twice.
 *
 * {@code version} backs optimistic concurrency in {@code TicketRepository}. The
 * state machine alone is a check-then-act: two threads can both read PAID and
 * both call {@link #close()} before either mutates, which would release the spot
 * twice. The service closes that race by saving with a compare-and-swap on
 * {@code version} BEFORE releasing the spot, so only the CAS winner frees it.
 * Modeled this way because at scale a ticket is a DB row touched by independent
 * exit terminals — there is no shared JVM lock to fall back on.
 */
public class ParkingTicket {
    private final String id;
    private final Vehicle vehicle;
    private final Instant entryTime;

    private String spotId;
    private int level;
    private Instant exitTime;
    private TicketStatus status = TicketStatus.PARKED;
    private Money fee = Money.zero();
    private long version = 0L;

    public ParkingTicket(String id, Vehicle vehicle, Instant entryTime) {
        this.id = id;
        this.vehicle = vehicle;
        this.entryTime = entryTime;
    }

    private ParkingTicket(String id,
                          Vehicle vehicle,
                          Instant entryTime,
                          String spotId,
                          int level,
                          Instant exitTime,
                          TicketStatus status,
                          Money fee,
                          long version) {
        this.id = id;
        this.vehicle = vehicle;
        this.entryTime = entryTime;
        this.spotId = spotId;
        this.level = level;
        this.exitTime = exitTime;
        this.status = status;
        this.fee = fee;
        this.version = version;
    }

    /** Detached snapshot used by repositories so callers cannot mutate stored state before CAS. */
    public ParkingTicket copy() {
        return new ParkingTicket(id, vehicle, entryTime, spotId, level, exitTime, status, fee, version);
    }

    /** Bind the claimed spot. Called once, right after a successful claim. */
    public void assignSpot(ParkingSpot spot) {
        this.spotId = spot.id();
        this.level = spot.level();
    }

    /** Driver requests checkout: freeze exit time and fee, await payment. */
    public void startCheckout(Instant exitTime, Money fee) {
        if (status != TicketStatus.PARKED && status != TicketStatus.LOST) {
            throw new InvalidTicketStateException("Cannot checkout ticket in status " + status);
        }
        this.exitTime = exitTime;
        this.fee = fee;
        this.status = TicketStatus.AWAITING_PAYMENT;
    }

    public void markLost() {
        if (status != TicketStatus.PARKED) {
            throw new InvalidTicketStateException("Only a parked ticket can be reported lost, was " + status);
        }
        this.status = TicketStatus.LOST;
    }

    public void markPaid() {
        if (status != TicketStatus.AWAITING_PAYMENT) {
            throw new InvalidTicketStateException("Cannot pay ticket in status " + status);
        }
        this.status = TicketStatus.PAID;
    }

    public void close() {
        if (status != TicketStatus.PAID) {
            throw new InvalidTicketStateException("Cannot close unpaid ticket in status " + status);
        }
        this.status = TicketStatus.CLOSED;
    }

    public void incrementVersion() {
        this.version++;
    }

    public String id() {
        return id;
    }

    public Vehicle vehicle() {
        return vehicle;
    }

    public Instant entryTime() {
        return entryTime;
    }

    public Instant exitTime() {
        return exitTime;
    }

    public String spotId() {
        return spotId;
    }

    public int level() {
        return level;
    }

    public TicketStatus status() {
        return status;
    }

    public Money fee() {
        return fee;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return String.format("Ticket[%s plate=%s spot=%s L%d status=%s fee=%s]",
                id, vehicle.licensePlate(), spotId, level, status, fee);
    }
}
