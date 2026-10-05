package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;

public record RequesterId(UUID value) {

    public RequesterId {
        if (value == null) {
            throw new IllegalArgumentException("RequesterId value must not be null");
        }
    }

    public static RequesterId newId() {
        return new RequesterId(UUID.randomUUID());
    }

    public static RequesterId of(UUID value) {
        return new RequesterId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
