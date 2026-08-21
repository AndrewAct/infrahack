package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

public record AssignmentId(UUID value) {

    public AssignmentId {
        if (value == null) {
            throw new IllegalArgumentException("AssignmentId value must not be null");
        }
    }

    public static AssignmentId newId() {
        return new AssignmentId(UUID.randomUUID());
    }

    public static AssignmentId of(UUID value) {
        return new AssignmentId(value);
    }

    /** Stable across retries of the same offer acceptance. */
    public static AssignmentId forOffer(OfferId offerId) {
        return new AssignmentId(UUID.nameUUIDFromBytes(
                ("assignment:" + offerId.value()).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
