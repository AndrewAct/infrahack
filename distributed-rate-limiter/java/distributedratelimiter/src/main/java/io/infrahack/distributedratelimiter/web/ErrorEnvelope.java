package io.infrahack.distributedratelimiter.web;

/** Stable JSON error shape returned by {@link ApiExceptionHandler}. */
public record ErrorEnvelope(String error, String message) {

    public static ErrorEnvelope of(String error, String message) {
        return new ErrorEnvelope(error, message);
    }
}
