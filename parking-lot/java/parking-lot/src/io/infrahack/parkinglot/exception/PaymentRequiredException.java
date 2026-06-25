package io.infrahack.parkinglot.exception;

/** Thrown when exit is attempted before the fee is fully paid. */
public class PaymentRequiredException extends RuntimeException {
    public PaymentRequiredException(String message) {
        super(message);
    }
}
