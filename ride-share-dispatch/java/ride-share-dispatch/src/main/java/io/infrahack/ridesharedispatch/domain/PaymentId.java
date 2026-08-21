package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;

public record PaymentId(UUID value) {

    public PaymentId {
        if (value == null) {
            throw new IllegalArgumentException("PaymentId value must not be null");
        }
    }

    public static PaymentId newId() {
        return new PaymentId(UUID.randomUUID());
    }

    public static PaymentId of(UUID value) {
        return new PaymentId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
