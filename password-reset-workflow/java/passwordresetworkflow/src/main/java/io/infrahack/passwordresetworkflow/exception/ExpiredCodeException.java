package io.infrahack.passwordresetworkflow.exception;

public final class ExpiredCodeException extends DomainException {

    public ExpiredCodeException() {
        super("code_expired", "Verification code has expired");
    }
}
