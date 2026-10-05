package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Simulated delivery: no real push provider in the MVP (see docs/DESIGN.md
 * "Notification lifecycle" for the foreground-WebSocket / background-push split a
 * production system would implement instead). What this class does model for real is
 * the dedup discipline every event-driven fan-out needs: {@link NotificationDeliveryRepository}
 * enforces {@code UNIQUE(event_id, recipient_id, channel)}, so a Kafka redelivery of
 * the same event can never produce a second notification.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final String PUSH_CHANNEL = "PUSH";

    private final NotificationDeliveryRepository deliveryRepository;
    private final DispatchMetrics metrics;

    public NotificationService(NotificationDeliveryRepository deliveryRepository, DispatchMetrics metrics) {
        this.deliveryRepository = deliveryRepository;
        this.metrics = metrics;
    }

    public void deliver(UUID eventId, UUID recipientId, String channel, String message) {
        boolean isNewDelivery = deliveryRepository.insertIfAbsent(UUID.randomUUID(), eventId, recipientId, channel);
        if (!isNewDelivery) {
            metrics.notificationDuplicateEventsTotal().increment();
            log.info("duplicate notification event skipped eventId={} recipientId={} channel={}",
                    eventId, recipientId, channel);
            return;
        }
        metrics.notificationDeliveriesTotal().increment();
        // Stand-in for a real push/WebSocket send -- see class javadoc.
        log.info("notification delivered recipientId={} channel={} message=\"{}\"", recipientId, channel, message);
    }
}
