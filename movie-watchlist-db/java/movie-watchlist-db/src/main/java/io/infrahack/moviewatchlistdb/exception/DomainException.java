package io.infrahack.moviewatchlistdb.exception;

/**
 * Base for expected business-rule failures (not-found, duplicate, ...).
 *
 * <p>These are deliberately {@link RuntimeException}s: the service throws one to short-circuit the
 * handler pipeline the moment a validation fails, and the web layer catches the base type in one
 * place. Each carries a stable machine-readable {@link #code()} (e.g. {@code watchlist_not_found})
 * that clients can branch on without parsing prose. It intentionally knows nothing about HTTP — the
 * status mapping lives in the web layer so the domain stays transport-agnostic.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
