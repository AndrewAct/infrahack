package io.infrahack.moviewatchlistdb.repository;

/**
 * Unchecked wrapper for persistence failures (e.g. {@link java.sql.SQLException}). Repositories throw
 * this so callers never handle checked SQL exceptions; the web layer maps it to a 500.
 */
public class RepositoryException extends RuntimeException {

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
