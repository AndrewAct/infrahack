package io.infrahack.passwordresetworkflow.exception;

public final class InvalidCodeException extends DomainException {

    public InvalidCodeException() {
        super("invalid_code", "Verification code does not match");
    }
}
