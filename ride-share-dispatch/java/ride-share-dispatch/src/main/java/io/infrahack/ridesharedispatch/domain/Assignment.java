package io.infrahack.ridesharedispatch.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * The durable trip/job record. {@code version} backs optimistic concurrency control:
 * every state transition is persisted as {@code UPDATE ... SET status=?, version=version+1
 * WHERE id=? AND version=?}. Two concurrent writers can both read version N, but only one
 * UPDATE matches at commit time -- the loser's rowsAffected is 0 and must reload and retry
 * or surface a conflict. See AssignmentRepository and docs/DESIGN.md "Concurrency control".
 */
public record Assignment(
        AssignmentId id,
        DispatchRequestId requestId,
        OfferId offerId,
        RequesterId requesterId,
        AgentId agentId,
        AssignmentStatus status,
        long version,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt
) {

    public Assignment transitionTo(AssignmentStatus newStatus, Instant now) {
        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "Illegal assignment transition %s -> %s".formatted(status, newStatus));
        }
        return new Assignment(
                id, requestId, offerId, requesterId, agentId, newStatus, version + 1,
                createdAt,
                newStatus == AssignmentStatus.IN_PROGRESS ? Optional.of(now) : startedAt,
                newStatus == AssignmentStatus.COMPLETED ? Optional.of(now) : completedAt);
    }
}
