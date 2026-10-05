package io.infrahack.ridesharedispatch.infrastructure.kafka;

import tools.jackson.databind.ObjectMapper;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.repository.ProcessedEventRepository;
import io.infrahack.ridesharedispatch.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Triggers payment asynchronously off {@code AssignmentCompleted}, in its own consumer
 * group ({@code ride-share-dispatch-payment}) so it receives every event independent
 * of NotificationEventConsumer's group -- Kafka fan-out, not competing consumption.
 *
 * <p>Kafka only guarantees at-least-once delivery: a rebalance, a consumer restart, or
 * a redelivered batch can hand this listener the same event twice. {@code processed_events}
 * (keyed by (event_id, consumer_name)) is the dedup ledger that makes that safe. The
 * winning transaction creates one durable payment; provider execution is separately
 * claimed and idempotent so it can be recovered after a crash.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final String CONSUMER_NAME = "payment";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PaymentService paymentService;
    private final TransactionTemplate transactionTemplate;

    public PaymentEventConsumer(ObjectMapper objectMapper, ProcessedEventRepository processedEventRepository,
                                 PaymentService paymentService, PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.paymentService = paymentService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = KafkaTopics.DISPATCH_EVENTS, groupId = "ride-share-dispatch-payment")
    public void onMessage(String envelopeJson) throws Exception {
        DomainEventEnvelope envelope = DomainEventEnvelope.parse(envelopeJson, objectMapper);
        if (!"AssignmentCompleted".equals(envelope.eventType())) {
            return;
        }
        UUID assignmentId = UUID.fromString(envelope.payload().get("assignmentId").toString());
        String operationId = transactionTemplate.execute(status -> {
            if (!processedEventRepository.markProcessed(envelope.eventId(), CONSUMER_NAME)) return null;
            return paymentService.prepareChargeForAssignment(AssignmentId.of(assignmentId));
        });
        if (operationId == null) {
            log.info("duplicate AssignmentCompleted delivery ignored eventId={}", envelope.eventId());
            return;
        }
        // Provider I/O is deliberately outside the inbox transaction. If the process
        // dies here, the durable CREATED payment is picked up by reconciliation.
        paymentService.processDuePayments();
    }
}
