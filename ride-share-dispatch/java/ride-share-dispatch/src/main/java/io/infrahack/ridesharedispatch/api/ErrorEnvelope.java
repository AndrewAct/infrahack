package io.infrahack.ridesharedispatch.api;

public record ErrorEnvelope(String code, String message) {

    public static ErrorEnvelope of(String code, String message) {
        return new ErrorEnvelope(code, message);
    }
}
