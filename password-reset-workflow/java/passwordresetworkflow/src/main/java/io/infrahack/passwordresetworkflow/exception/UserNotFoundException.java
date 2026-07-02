package io.infrahack.passwordresetworkflow.exception;

public final class UserNotFoundException extends DomainException {

    public UserNotFoundException(String email) {
        super("user_not_found", "No user with email " + email);
    }
}
