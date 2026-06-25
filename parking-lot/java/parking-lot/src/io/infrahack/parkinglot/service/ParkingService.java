package io.infrahack.parkinglot.service;

import io.infrahack.parkinglot.enums.TicketStatus;
import io.infrahack.parkinglot.exception.InvalidTicketStateException;
import io.infrahack.parkinglot.exception.NoAvailableSpotException;
import io.infrahack.parkinglot.exception.PaymentRequiredException;
import io.infrahack.parkinglot.exception.VehicleAlreadyParkedException;
import io.infrahack.parkinglot.model.Money;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingSpot;
import io.infrahack.parkinglot.model.ParkingTicket;
import io.infrahack.parkinglot.model.User;
import io.infrahack.parkinglot.model.Vehicle;
import io.infrahack.parkinglot.repository.TicketRepository;
import io.infrahack.parkinglot.strategy.PricingStrategy;
import io.infrahack.parkinglot.strategy.SpotAssignmentStrategy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver-facing entry/exit flow. Owns two invariants:
 *   1. a license plate holds at most one active ticket ({@code activeByPlate}),
 *   2. a spot is freed exactly once (CAS on the ticket before release).
 *
 * The four-step exit (checkout -> pay -> exit) models the real barrier: the gate
 * only lifts after payment clears.
 */
public class ParkingService {
    private final ParkingLot lot;
    private final SpotAssignmentStrategy assignmentStrategy;
    private final PricingStrategy pricingStrategy;
    private final TicketRepository ticketRepository;
    private final PermissionService permissionService;
    private final MetricsCollector metrics;
    private final AuditService audit;
    private final Money lostTicketFee;

    /** Active ticket ids by plate; the reservation that enforces "one ticket per plate". */
    private final Map<String, String> activeByPlate = new ConcurrentHashMap<>();

    public ParkingService(ParkingLot lot,
                          SpotAssignmentStrategy assignmentStrategy,
                          PricingStrategy pricingStrategy,
                          TicketRepository ticketRepository,
                          PermissionService permissionService,
                          MetricsCollector metrics,
                          AuditService audit,
                          Money lostTicketFee) {
        this.lot = lot;
        this.assignmentStrategy = assignmentStrategy;
        this.pricingStrategy = pricingStrategy;
        this.ticketRepository = ticketRepository;
        this.permissionService = permissionService;
        this.metrics = metrics;
        this.audit = audit;
        this.lostTicketFee = lostTicketFee;
    }

    /**
     * Enter the lot. Reserves the plate first (atomic putIfAbsent) so a
     * duplicate entry can't burn a spot, then claims a compatible spot.
     */
    public ParkingTicket park(User user, Vehicle vehicle) {
        permissionService.requireDriver(user);
        String plate = vehicle.licensePlate();

        ParkingTicket ticket = new ParkingTicket(UUID.randomUUID().toString(), vehicle, Instant.now());
        if (activeByPlate.putIfAbsent(plate, ticket.id()) != null) {
            metrics.increment("parking.rejected.duplicate");
            throw new VehicleAlreadyParkedException("Vehicle already parked: " + plate);
        }

        Optional<ParkingSpot> spot = lot.claimSpot(vehicle, assignmentStrategy);
        if (spot.isEmpty()) {
            activeByPlate.remove(plate, ticket.id());
            metrics.increment("parking.rejected.full");
            audit.record(user, "ENTRY_DENIED_FULL", plate);
            throw new NoAvailableSpotException("No spot available for " + vehicle.type() + " " + plate);
        }

        ticket.assignSpot(spot.get());
        ticketRepository.save(ticket, ticket.version());
        metrics.increment("parking.entry");
        metrics.setGauge("parking.occupied", lot.occupancy().occupiedSpots());
        audit.record(user, "ENTERED", ticket.id());
        return ticket;
    }

    /** Compute and freeze the fee; the stay is now awaiting payment. Returns the amount due. */
    public Money checkout(User user, String ticketId) {
        permissionService.requireDriver(user);
        ParkingTicket ticket = require(ticketId);

        long expectedVersion = ticket.version();
        Money fee = pricingStrategy.computeFee(ticket, Instant.now());
        ticket.startCheckout(Instant.now(), fee);
        ticketRepository.save(ticket, expectedVersion);
        audit.record(user, "CHECKOUT", ticketId);
        return fee;
    }

    /** Pay the due amount. Under/empty payment is rejected; overpayment is accepted. */
    public void pay(User user, String ticketId, Money amount) {
        permissionService.requireDriver(user);
        ParkingTicket ticket = require(ticketId);
        if (amount.isGreaterThan(ticket.fee()) || amount.equals(ticket.fee())) {
            long expectedVersion = ticket.version();
            ticket.markPaid();
            ticketRepository.save(ticket, expectedVersion);
            metrics.add("parking.revenue.cents", ticket.fee().cents());
            audit.record(user, "PAID", ticketId);
        } else {
            throw new PaymentRequiredException(
                    "Insufficient payment " + amount + " for fee " + ticket.fee());
        }
    }

    /**
     * Lift the barrier and free the spot. The CAS save happens BEFORE the
     * release, so if two threads both reach exit only the version winner frees
     * the spot; the loser gets a StaleObjectException and the spot is freed once.
     */
    public void exit(User user, String ticketId) {
        permissionService.requireDriver(user);
        ParkingTicket ticket = require(ticketId);
        if (ticket.status() != TicketStatus.PAID) {
            throw new PaymentRequiredException("Ticket " + ticketId + " is not paid: " + ticket.status());
        }

        long expectedVersion = ticket.version();
        ticket.close();
        ticketRepository.save(ticket, expectedVersion); // CAS gate: only the winner proceeds

        lot.findSpot(ticket.level(), ticket.spotId()).ifPresent(lot::releaseSpot);
        activeByPlate.remove(ticket.vehicle().licensePlate(), ticket.id());
        metrics.increment("parking.exit");
        metrics.setGauge("parking.occupied", lot.occupancy().occupiedSpots());
        audit.record(user, "EXITED", ticketId);
    }

    /**
     * Driver lost the physical ticket: charge a flat penalty and move straight
     * to awaiting-payment. Returns the penalty due.
     */
    public Money reportLostTicket(User user, String licensePlate) {
        permissionService.requireDriver(user);
        String ticketId = activeByPlate.get(licensePlate);
        if (ticketId == null) {
            throw new InvalidTicketStateException("No active ticket for plate " + licensePlate);
        }
        ParkingTicket ticket = require(ticketId);
        long expectedVersion = ticket.version();
        ticket.markLost();
        ticket.startCheckout(Instant.now(), lostTicketFee);
        ticketRepository.save(ticket, expectedVersion);
        metrics.increment("parking.lost_ticket");
        audit.record(user, "LOST_TICKET", ticket.id());
        return lostTicketFee;
    }

    private ParkingTicket require(String ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new InvalidTicketStateException("Unknown ticket " + ticketId));
    }
}
