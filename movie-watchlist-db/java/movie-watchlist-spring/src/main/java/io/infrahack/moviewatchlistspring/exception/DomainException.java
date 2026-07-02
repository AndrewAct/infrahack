package io.infrahack.moviewatchlistspring.exception;

/**
 * Base for expected business-rule failures. Carries a stable machine {@link #code()} but knows nothing
 * about HTTP — the web layer ({@code @RestControllerAdvice}) owns the status mapping.
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
