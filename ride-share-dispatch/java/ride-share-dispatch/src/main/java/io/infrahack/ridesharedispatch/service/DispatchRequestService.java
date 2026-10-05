package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.domain.DriverId;
import io.infrahack.ridesharedispatch.domain.DispatchOffer;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.DispatchRequestStatus;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.OfferId;
import io.infrahack.ridesharedispatch.domain.OfferStatus;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.domain.exception.IdempotencyConflictException;
import io.infrahack.ridesharedispatch.domain.exception.NotFoundException;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.infrastructure.redis.DriverReservationStore;
import io.infrahack.ridesharedispatch.repository.DispatchOfferRepository;
import io.infrahack.ridesharedispatch.repository.DispatchRequestRepository;
import io.infrahack.ridesharedispatch.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent command intake + synchronous first matching attempt. See docs/DESIGN.md
 * "Idempotency lifecycle" and "Reservation algorithm".
 *
 * <p>Uses an explicit {@link TransactionTemplate} rather than {@code @Transactional}
 * on private helper methods. {@code @Transactional} only works through the Spring AOP
 * proxy; a same-class call such as {@code this.insertAndRecordEvent(...)} bypasses the
 * proxy entirely and the annotation would be silently ignored. {@code TransactionTemplate}
 * also makes the transaction boundary visible at the call site: the Redis matching call
 * below intentionally happens OUTSIDE any transaction, so a DB connection is never held
 * open across a network call to another system.
 */
@Service
public class DispatchRequestService {

    private static final Logger log = LoggerFactory.getLogger(DispatchRequestService.class);

    public record CreateCommand(String serviceType, GeoPoint origin, GeoPoint destination) {
    }

    public record CreateResult(DispatchRequest request, Optional<DispatchOffer> offer, boolean wasReplay) {
    }

    private final DispatchRequestRepository requestRepository;
    private final DispatchOfferRepository offerRepository;
    private final OutboxRepository outboxRepository;
    private final MatchingService matchingService;
    private final DispatchProperties properties;
    private final DispatchMetrics metrics;
    private final DriverReservationStore reservationStore;
    private final TransactionTemplate transactionTemplate;

    public DispatchRequestService(DispatchRequestRepository requestRepository,
                                   DispatchOfferRepository offerRepository,
                                   OutboxRepository outboxRepository,
                                   MatchingService matchingService,
                                   DispatchProperties properties,
                                   DispatchMetrics metrics,
                                   DriverReservationStore reservationStore,
                                   PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.offerRepository = offerRepository;
        this.outboxRepository = outboxRepository;
        this.matchingService = matchingService;
        this.properties = properties;
        this.metrics = metrics;
        this.reservationStore = reservationStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * {@code Idempotency-Key} + requester is the logical command identity. A retried
     * POST with the same key and the same payload returns the original DispatchRequest;
     * the same key with a different payload is a caller error (409), never a silent
     * merge. This is API idempotency, distinct from the driver-reservation concurrency
     * control performed a few lines down -- see docs/DESIGN.md "Idempotency vs
     * concurrency control".
     */
    public CreateResult createOrReplay(RequesterId requesterId, String idempotencyKey, CreateCommand command) {
        String fingerprint = RequestFingerprint.of(command.serviceType(), command.origin(), command.destination());
        DispatchRequestId newId = DispatchRequestId.newId();
        Instant now = Instant.now();

        DispatchRequest candidate = new DispatchRequest(newId, requesterId, idempotencyKey, fingerprint,
                DispatchRequestStatus.SEARCHING, command.serviceType(), command.origin(), command.destination(),
                Optional.empty(), now, now);

        boolean created = insertAndRecordEvent(candidate);

        if (!created) {
            DispatchRequest existing = requestRepository.findByRequesterAndKey(requesterId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Insert lost the ON CONFLICT race with no row to read"));
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            metrics.dispatchIdempotencyReplaysTotal().increment();
            log.info("idempotent replay requestId={}", existing.id());
            Optional<DispatchOffer> existingOffer = matchIfNeeded(existing);
            return new CreateResult(existing, existingOffer, true);
        }

        metrics.dispatchRequestsTotal().increment();
        Optional<DispatchOffer> offer = matchIfNeeded(candidate);

        return new CreateResult(candidate, offer, false);
    }

    private Optional<DispatchOffer> matchIfNeeded(DispatchRequest request) {
        Optional<DispatchOffer> latest = offerRepository.findLatestByRequestId(request.id());
        if (request.status() != DispatchRequestStatus.SEARCHING) {
            return latest;
        }
        Optional<DispatchOffer> livePending = latest.filter(offer -> offer.status() == OfferStatus.PENDING)
                .filter(offer -> !offer.isExpired(Instant.now()));
        if (livePending.isPresent()) return livePending;
        latest.filter(offer -> offer.status() == OfferStatus.PENDING).ifPresent(expired -> {
            offerRepository.transitionFromPending(expired.id(), OfferStatus.EXPIRED);
            reservationStore.release(expired.driverId(), expired.reservationToken());
            metrics.reservationReleased();
        });

        Instant claimUntil = Instant.now().plusMillis(properties.matchingTimeoutMs() + 1_000L);
        UUID matchingWorkerId = UUID.randomUUID();
        if (!requestRepository.tryClaimMatching(request.id(), matchingWorkerId, claimUntil)) {
            return offerRepository.findLatestByRequestId(request.id());
        }

        try {
            Optional<MatchingService.MatchOutcome> outcome = matchingService.attemptMatch(request);
            if (outcome.isEmpty()) return Optional.empty();
            MatchingService.MatchOutcome match = outcome.orElseThrow();
            try {
                return Optional.of(createOffer(request, match.driverId(), match.reservationToken()));
            } catch (RuntimeException databaseFailure) {
                reservationStore.release(match.driverId(), match.reservationToken());
                metrics.reservationReleased();
                throw databaseFailure;
            }
        } finally {
            requestRepository.releaseMatchingClaim(request.id(), matchingWorkerId);
        }
    }

    private boolean insertAndRecordEvent(DispatchRequest candidate) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean created = requestRepository.insertIfAbsent(candidate);
            if (created) {
                outboxRepository.append("DispatchRequestCreated", candidate.id().value(), Map.of(
                        "requestId", candidate.id().value().toString(),
                        "requesterId", candidate.requesterId().value().toString(),
                        "serviceType", candidate.serviceType()));
            }
            return created;
        }));
    }

    private DispatchOffer createOffer(DispatchRequest request, DriverId driverId, UUID reservationToken) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            DispatchOffer offer = new DispatchOffer(
                    OfferId.newId(), request.id(), driverId, OfferStatus.PENDING, reservationToken,
                    now.plusSeconds(properties.offerTtlSeconds()), now, now);
            offerRepository.insert(offer);
            outboxRepository.append("DriverReserved", driverId.value(), Map.of(
                    "requestId", request.id().value().toString(),
                    "driverId", driverId.value().toString(),
                    "offerId", offer.id().value().toString()));
            return offer;
        });
    }

    public DispatchRequest requireById(DispatchRequestId id) {
        return requestRepository.findById(id).orElseThrow(() -> new NotFoundException("DispatchRequest", id));
    }

    public Optional<DispatchOffer> latestOfferFor(DispatchRequestId id) {
        return offerRepository.findLatestByRequestId(id);
    }
}
