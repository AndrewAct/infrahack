package io.infrahack.ridesharedispatch.domain;

/**
 * <pre>
 * SEARCHING -&gt; MATCHED
 *           -&gt; CANCELLED
 *           -&gt; EXPIRED
 * </pre>
 */
public enum DispatchRequestStatus {
    SEARCHING,
    MATCHED,
    CANCELLED,
    EXPIRED;

    public boolean canTransitionTo(DispatchRequestStatus target) {
        return this == SEARCHING && (target == MATCHED || target == CANCELLED || target == EXPIRED);
    }
}
