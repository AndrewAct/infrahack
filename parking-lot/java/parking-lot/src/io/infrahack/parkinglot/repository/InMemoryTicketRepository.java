package io.infrahack.parkinglot.repository;

import io.infrahack.parkinglot.exception.StaleObjectException;
import io.infrahack.parkinglot.model.ParkingTicket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ticket store standing in for a row-versioned DB table. Reads return
 * detached snapshots, and {@link #save} copies the caller's changed ticket into
 * storage only after the version check passes. That keeps a failed CAS from
 * leaking pre-save mutations into the repository.
 */
public class InMemoryTicketRepository implements TicketRepository {
    private final Map<String, ParkingTicket> tickets = new ConcurrentHashMap<>();

    @Override
    public Optional<ParkingTicket> findById(String id) {
        ParkingTicket ticket = tickets.get(id);
        return ticket == null ? Optional.empty() : Optional.of(ticket.copy());
    }

    @Override
    public synchronized void save(ParkingTicket ticket, long expectedVersion) {
        ParkingTicket existing = tickets.get(ticket.id());
        if (existing != null && existing.version() != expectedVersion) {
            throw new StaleObjectException(
                    "Ticket " + ticket.id() + " expected version " + expectedVersion
                            + " but found " + existing.version());
        }
        ParkingTicket stored = ticket.copy();
        stored.incrementVersion();
        tickets.put(stored.id(), stored);
    }
}
