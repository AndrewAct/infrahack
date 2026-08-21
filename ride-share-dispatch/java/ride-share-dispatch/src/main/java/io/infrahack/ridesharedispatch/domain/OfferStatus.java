package io.infrahack.ridesharedispatch.domain;

/**
 * <pre>
 * PENDING -&gt; ACCEPTED
 *         -&gt; REJECTED
 *         -&gt; EXPIRED
 * </pre>
 */
public enum OfferStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED;

    public boolean canTransitionTo(OfferStatus target) {
        return this == PENDING && (target == ACCEPTED || target == REJECTED || target == EXPIRED);
    }
}
