package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;

public record OfferId(UUID value) {

    public OfferId {
        if (value == null) {
            throw new IllegalArgumentException("OfferId value must not be null");
        }
    }

    public static OfferId newId() {
        return new OfferId(UUID.randomUUID());
    }

    public static OfferId of(UUID value) {
        return new OfferId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
