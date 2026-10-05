package io.infrahack.ridesharedispatch.infrastructure.kafka;

import tools.jackson.databind.ObjectMapper;
import io.infrahack.ridesharedispatch.repository.ProcessedEventRepository;
import io.infrahack.ridesharedispatch.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Independent consumer group ({@code ride-share-dispatch-notification}) from
 * PaymentEventConsumer -- both receive every event; Kafka fan-out via separate groups,
 * not a shared queue. MVP scope only reacts to {@code AssignmentCompleted} (it already
 * carries the requester id to notify); PaymentSucceeded/Failed are easy same-shape
 * additions once a real push provider exists.
 */
@Component
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);
    private static final String CONSUMER_NAME = "notification";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    public NotificationEventConsumer(ObjectMapper objectMapper, ProcessedEventRepository processedEventRepository,
                                      NotificationService notificationService,
                                      PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = KafkaTopics.DISPATCH_EVENTS, groupId = "ride-share-dispatch-notification")
    public void onMessage(String envelopeJson) throws Exception {
        DomainEventEnvelope envelope = DomainEventEnvelope.parse(envelopeJson, objectMapper);
        if (!"AssignmentCompleted".equals(envelope.eventType())) {
            return;
        }
        UUID requesterId = UUID.fromString(envelope.payload().get("requesterId").toString());
        boolean processed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!processedEventRepository.markProcessed(envelope.eventId(), CONSUMER_NAME)) return false;
            notificationService.deliver(envelope.eventId(), requesterId, NotificationService.PUSH_CHANNEL,
                    "Your trip is complete.");
            return true;
        }));
        if (!processed) {
            log.info("duplicate AssignmentCompleted delivery ignored eventId={}", envelope.eventId());
        }
    }
}
