package io.infrahack.passwordresetworkflow.web;

/**
 * JSON error shape: {@code {"error":{"code":"...","message":"..."}}}. A stable envelope means
 * clients always parse errors the same way regardless of which failure occurred.
 */
public record ErrorEnvelope(Body error) {

    public record Body(String code, String message) {}

    public static ErrorEnvelope of(String code, String message) {
        return new ErrorEnvelope(new Body(code, message));
    }
}
