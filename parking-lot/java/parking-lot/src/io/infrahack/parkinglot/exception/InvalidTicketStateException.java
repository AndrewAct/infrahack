package io.infrahack.parkinglot.exception;

/** Thrown when a ticket lifecycle transition is illegal (e.g. exiting a closed ticket). */
public class InvalidTicketStateException extends RuntimeException {
    public InvalidTicketStateException(String message) {
        super(message);
    }
}
