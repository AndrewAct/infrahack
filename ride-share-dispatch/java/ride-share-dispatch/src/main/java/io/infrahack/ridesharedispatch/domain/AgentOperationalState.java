package io.infrahack.ridesharedispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Snapshot of an agent's hot state as currently held in Redis. Note {@code status} only
 * ever carries {@link AgentOperationalStatus#OFFLINE}, {@link AgentOperationalStatus#AVAILABLE},
 * or {@link AgentOperationalStatus#OCCUPIED} -- RESERVED is never persisted here, it is
 * represented purely by the existence of a TTL reservation key (see AgentReservationStore).
 */
public record AgentOperationalState(
        AgentId agentId,
        AgentOperationalStatus status,
        Optional<GeoPoint> location,
        Optional<String> spatialCell,
        Optional<Instant> lastSeen,
        long sequenceNumber,
        Optional<AssignmentId> activeAssignmentId,
        Optional<String> serviceType,
        Optional<Agent.AccountStatus> accountStatus
) {

    public boolean isFresh(Instant now, Duration freshnessWindow) {
        return lastSeen.isPresent() && !lastSeen.get().isBefore(now.minus(freshnessWindow));
    }

    public boolean isMatchable(Instant now, Duration freshnessWindow, String requiredServiceType) {
        return status == AgentOperationalStatus.AVAILABLE
                && location.isPresent()
                && isFresh(now, freshnessWindow)
                && activeAssignmentId.isEmpty()
                && serviceType.filter(requiredServiceType::equals).isPresent()
                && accountStatus.filter(status -> status == Agent.AccountStatus.ACTIVE).isPresent();
    }
}
