package io.infrahack.ridesharedispatch.domain;

/**
 * <pre>
 * CREATED -&gt; IN_PROGRESS -&gt; COMPLETED
 *         -&gt; CANCELLED
 * IN_PROGRESS -&gt; CANCELLED
 * </pre>
 */
public enum AssignmentStatus {
    CREATED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(AssignmentStatus target) {
        return switch (this) {
            case CREATED -> target == IN_PROGRESS || target == CANCELLED;
            case IN_PROGRESS -> target == COMPLETED || target == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
