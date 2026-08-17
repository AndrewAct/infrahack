package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;

public record DispatchRequestId(UUID value) {

    public DispatchRequestId {
        if (value == null) {
            throw new IllegalArgumentException("DispatchRequestId value must not be null");
        }
    }

    public static DispatchRequestId newId() {
        return new DispatchRequestId(UUID.randomUUID());
    }

    public static DispatchRequestId of(UUID value) {
        return new DispatchRequestId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
