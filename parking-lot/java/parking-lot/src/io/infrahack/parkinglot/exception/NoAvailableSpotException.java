package io.infrahack.parkinglot.exception;

/** Thrown at entry when no compatible spot is free (lot full for this vehicle class). */
public class NoAvailableSpotException extends RuntimeException {
    public NoAvailableSpotException(String message) {
        super(message);
    }
}
