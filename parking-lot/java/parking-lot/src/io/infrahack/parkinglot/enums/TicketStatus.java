package io.infrahack.parkinglot.enums;

/**
 * Ticket lifecycle. Legal transitions:
 * PARKED -> AWAITING_PAYMENT -> PAID -> CLOSED
 * PARKED -> LOST -> AWAITING_PAYMENT -> PAID -> CLOSED
 * CLOSED is terminal.
 */
public enum TicketStatus {
    PARKED,
    AWAITING_PAYMENT,
    PAID,
    CLOSED,
    LOST
}
