package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.Payment;
import io.infrahack.ridesharedispatch.domain.PaymentStatus;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.repository.PaymentRepository;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import io.infrahack.ridesharedispatch.service.FakePaymentProvider;
import io.infrahack.ridesharedispatch.service.OfferService;
import io.infrahack.ridesharedispatch.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #8: a payment retry with the same logical payment identity cannot double
 * charge. See FakePaymentProvider's javadoc for how the ledger vs. caller-observed
 * outcome split is modeled.
 */
class PaymentServiceIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchRequestService dispatchRequestService;

    @Autowired
    private OfferService offerService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    private Assignment createAssignment() {
        createAvailableDriverAt(new GeoPoint(37.7750, -122.4195));
        DispatchRequestService.CreateCommand command = new DispatchRequestService.CreateCommand(
                "STANDARD", new GeoPoint(37.7749, -122.4194), new GeoPoint(37.7849, -122.4094));
        DispatchRequestService.CreateResult result = dispatchRequestService.createOrReplay(
                RequesterId.newId(), "key-" + UUID.randomUUID(), command);
        Assignment assignment = offerService.accept(result.offer().orElseThrow().id());
        jdbc.update("UPDATE assignments SET status = 'COMPLETED', version = version + 1, completed_at = now() "
                + "WHERE assignment_id = ?", assignment.id().value());
        return assignment;
    }

    @Test
    void chargingTwiceForTheSameAssignmentOnlyChargesOnce() {
        Assignment assignment = createAssignment();
        String operationId = assignment.id().value() + ":final-charge";

        paymentService.chargeForAssignment(assignment.id());
        paymentService.chargeForAssignment(assignment.id()); // e.g. a redelivered Kafka event

        Payment payment = paymentRepository.findByOperationId(operationId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fakePaymentProvider.chargeComputationCount(operationId)).isEqualTo(1);

        Integer paymentRows = jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE operation_id = ?", Integer.class, operationId);
        assertThat(paymentRows).isEqualTo(1);
    }

    @Test
    void timeoutThenRetryWithSameOperationIdDoesNotDoubleCharge() {
        Assignment assignment = createAssignment();
        String operationId = assignment.id().value() + ":final-charge";

        fakePaymentProvider.simulateTimeoutOnNextCall(operationId);
        paymentService.chargeForAssignment(assignment.id());

        Payment afterTimeout = paymentRepository.findByOperationId(operationId).orElseThrow();
        assertThat(afterTimeout.status()).isEqualTo(PaymentStatus.PROCESSING);

        // Reconciliation retry, reusing the same operation id.
        jdbc.update("UPDATE payments SET next_attempt_at = now() - interval '1 second' WHERE operation_id = ?", operationId);
        paymentService.reconcilePendingPayments(Instant.now().plusSeconds(1));

        Payment afterReconciliation = paymentRepository.findByOperationId(operationId).orElseThrow();
        assertThat(afterReconciliation.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fakePaymentProvider.chargeComputationCount(operationId))
                .as("the real charge computation must run at most once even though the caller saw a timeout then retried")
                .isEqualTo(1);
    }
}
