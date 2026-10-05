package io.infrahack.ridesharedispatch.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically claims a bounded batch of due payments. PaymentService applies capped
 * exponential backoff and eventually moves repeated uncertain outcomes to UNKNOWN.
 */
@Component
public class PaymentReconciliationScheduler {

    private final PaymentService paymentService;

    public PaymentReconciliationScheduler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${dispatch.payment.reconciliation-interval-ms}")
    public void reconcile() {
        paymentService.processDuePayments();
    }
}
