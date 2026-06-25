package io.infrahack.parkinglot.exception;

/** Optimistic-concurrency violation: the ticket was modified by another writer. */
public class StaleObjectException extends RuntimeException {
    public StaleObjectException(String message) {
        super(message);
    }
}
