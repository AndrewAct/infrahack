package io.infrahack.parkinglot.exception;

/** Enforces the invariant: a license plate may hold at most one active ticket. */
public class VehicleAlreadyParkedException extends RuntimeException {
    public VehicleAlreadyParkedException(String message) {
        super(message);
    }
}
