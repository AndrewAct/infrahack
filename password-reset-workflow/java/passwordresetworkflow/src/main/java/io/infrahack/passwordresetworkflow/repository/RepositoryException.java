package io.infrahack.passwordresetworkflow.repository;

/** Wraps checked persistence errors so callers see one unchecked failure type. */
public final class RepositoryException extends RuntimeException {

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
