package io.infrahack.ridesharedispatch.domain.exception;

/**
 * Base for every domain-level failure. HTTP-agnostic on purpose -- the mapping to a
 * status code lives in one place, {@code api.ApiExceptionHandler}, not scattered
 * across services.
 */
public abstract sealed class DomainException extends RuntimeException
        permits IdempotencyConflictException, NotFoundException, ConflictException {

    protected DomainException(String message) {
        super(message);
    }

    public abstract String code();
}
