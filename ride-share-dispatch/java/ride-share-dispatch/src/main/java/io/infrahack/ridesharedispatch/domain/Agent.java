package io.infrahack.ridesharedispatch.domain;

import java.time.Instant;

/**
 * Durable agent profile. Deliberately narrow: identity, service offering, rating,
 * and account standing. Anything that changes on the order of seconds (location,
 * availability, current assignment) is hot operational state and lives in Redis --
 * see {@link io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore}.
 * Mixing the two into one row would mean a location ping does a Postgres write, and
 * this module exists partly to demonstrate why that is the wrong choice at scale.
 */
public record Agent(
        AgentId id,
        String displayName,
        String serviceType,
        double rating,
        AccountStatus accountStatus,
        Instant createdAt
) {

    public enum AccountStatus {
        ACTIVE,
        SUSPENDED
    }

    public boolean isEligibleForDispatch() {
        return accountStatus == AccountStatus.ACTIVE;
    }
}
