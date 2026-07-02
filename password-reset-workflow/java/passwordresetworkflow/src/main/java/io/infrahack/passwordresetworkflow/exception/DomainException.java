package io.infrahack.passwordresetworkflow.exception;

/** Base for business-rule violations; {@code code} is the stable machine-readable error identifier. */
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
