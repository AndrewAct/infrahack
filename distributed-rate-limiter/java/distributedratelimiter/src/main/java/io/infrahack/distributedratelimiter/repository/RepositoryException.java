package io.infrahack.distributedratelimiter.repository;

/** Wraps persistence failures (e.g. {@code SQLException}) so callers depend on repository interfaces, not JDBC. */
public final class RepositoryException extends RuntimeException {

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
