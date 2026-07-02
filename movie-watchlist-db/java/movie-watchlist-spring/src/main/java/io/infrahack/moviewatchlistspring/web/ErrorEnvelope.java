package io.infrahack.moviewatchlistspring.web;

/** JSON error shape: {@code {"error":{"code":"...","message":"..."}}}. */
public record ErrorEnvelope(Body error) {

    public record Body(String code, String message) {}

    public static ErrorEnvelope of(String code, String message) {
        return new ErrorEnvelope(new Body(code, message));
    }
}
