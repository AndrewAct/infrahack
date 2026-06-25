package io.infrahack.parkinglot.repository;

import io.infrahack.parkinglot.model.ParkingTicket;

import java.util.Optional;

public interface TicketRepository {
    Optional<ParkingTicket> findById(String id);

    /**
     * Persist with optimistic concurrency. Throws
     * {@code StaleObjectException} if the stored version no longer matches
     * {@code expectedVersion}, i.e. another writer got there first. On success
     * the version is advanced by one.
     */
    void save(ParkingTicket ticket, long expectedVersion);
}
