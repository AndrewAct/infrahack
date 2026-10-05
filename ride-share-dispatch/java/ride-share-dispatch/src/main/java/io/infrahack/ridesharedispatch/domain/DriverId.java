package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;

/**
 * Value-typed identifier. Wrapping the raw UUID stops an DriverId and a RequesterId
 * from being accidentally interchangeable at a method boundary -- the compiler
 * catches what a bare UUID parameter list would not.
 */
public record DriverId(UUID value) {

    public DriverId {
        if (value == null) {
            throw new IllegalArgumentException("DriverId value must not be null");
        }
    }

    public static DriverId newId() {
        return new DriverId(UUID.randomUUID());
    }

    public static DriverId of(UUID value) {
        return new DriverId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
