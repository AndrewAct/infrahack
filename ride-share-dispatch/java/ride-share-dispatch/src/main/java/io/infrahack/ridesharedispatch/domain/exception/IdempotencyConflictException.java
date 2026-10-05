package io.infrahack.ridesharedispatch.domain.exception;

/**
 * The same (requester, idempotency key) pair was reused for a logically different
 * request payload. This is a caller bug, not a retry -- a genuine retry must send an
 * identical fingerprint. See DispatchRequestService.
 */
public final class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException() {
        super("The Idempotency-Key was already used with a different request payload");
    }

    @Override
    public String code() {
        return "idempotency_key_reused";
    }
}
