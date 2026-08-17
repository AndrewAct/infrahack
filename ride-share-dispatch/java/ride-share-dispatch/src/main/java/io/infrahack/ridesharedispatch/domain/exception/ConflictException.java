package io.infrahack.ridesharedispatch.domain.exception;

/**
 * A state-machine or concurrency conflict: illegal transition, OCC version mismatch,
 * expired/lost reservation, or a double-accept/double-complete race loser. Distinct
 * from {@link IdempotencyConflictException}, which is specifically "same key, different
 * payload" -- see docs/DESIGN.md "Idempotency vs concurrency control".
 */
public final class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "conflict";
    }
}
