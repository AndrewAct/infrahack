package io.infrahack.ridesharedispatch.domain;

import java.util.UUID;

/**
 * Value-typed identifier. Wrapping the raw UUID stops an AgentId and a RequesterId
 * from being accidentally interchangeable at a method boundary -- the compiler
 * catches what a bare UUID parameter list would not.
 */
public record AgentId(UUID value) {

    public AgentId {
        if (value == null) {
            throw new IllegalArgumentException("AgentId value must not be null");
        }
    }

    public static AgentId newId() {
        return new AgentId(UUID.randomUUID());
    }

    public static AgentId of(UUID value) {
        return new AgentId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
