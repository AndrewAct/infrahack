package io.infrahack.ridesharedispatch.domain;

/**
 * <pre>
 * CREATED -&gt; PROCESSING -&gt; SUCCEEDED
 *                        -&gt; FAILED
 *                        -&gt; PROCESSING (retry after an uncertain/timed-out attempt)
 * </pre>
 * A timeout does not mean the charge failed -- see FakePaymentProvider. The operation
 * is left in PROCESSING and reconciled using the same operation id, never re-created.
 */
public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case CREATED -> target == PROCESSING;
            case PROCESSING -> target == SUCCEEDED || target == FAILED || target == PROCESSING || target == UNKNOWN;
            case SUCCEEDED, FAILED, UNKNOWN -> false;
        };
    }
}
