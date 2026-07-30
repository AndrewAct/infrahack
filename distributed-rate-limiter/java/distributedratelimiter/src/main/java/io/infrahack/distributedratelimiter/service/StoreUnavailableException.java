package io.infrahack.distributedratelimiter.service;

/** The token bucket store (Redis) could not be reached or timed out. */
public final class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
