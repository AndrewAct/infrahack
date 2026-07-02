package io.infrahack.passwordresetworkflow.exception;

public final class NoActiveResetRequestException extends DomainException {

    public NoActiveResetRequestException(String email) {
        super("no_active_request", "No active password reset request for " + email);
    }
}
