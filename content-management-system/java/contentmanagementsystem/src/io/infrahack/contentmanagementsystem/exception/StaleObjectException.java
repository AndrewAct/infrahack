package io.infrahack.contentmanagementsystem.exception;

public class StaleObjectException extends RuntimeException {
    public StaleObjectException(String message) {
        super(message);
    }
}
