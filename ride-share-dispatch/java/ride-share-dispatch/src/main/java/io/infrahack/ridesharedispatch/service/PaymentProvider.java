package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.Money;

/**
 * Seam for an external payment processor. The MVP ships only {@link FakePaymentProvider}
 * -- the point of this module is the distributed side effects (idempotent charge,
 * uncertain outcomes, reconciliation), not a real PSP integration.
 */
public interface PaymentProvider {

    enum ChargeOutcome {
        SUCCEEDED,
        FAILED,
        /** The provider may or may not have completed the charge -- the caller simply
         *  does not know yet. Must never be treated as FAILED. */
        TIMEOUT
    }

    /**
     * {@code operationId} is the stable logical identity of one charge (see Payment
     * javadoc). Calling this twice with the same operationId must only ever charge once,
     * regardless of how many times the caller retries after a TIMEOUT.
     */
    ChargeOutcome charge(String operationId, Money amount);
}
