package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.domain.AssignmentStatus;
import io.infrahack.ridesharedispatch.domain.DispatchOffer;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.DispatchRequestStatus;
import io.infrahack.ridesharedispatch.domain.OfferId;
import io.infrahack.ridesharedispatch.domain.OfferStatus;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.domain.exception.NotFoundException;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentReservationStore;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.AssignmentRepository;
import io.infrahack.ridesharedispatch.repository.DispatchOfferRepository;
import io.infrahack.ridesharedispatch.repository.DispatchRequestRepository;
import io.infrahack.ridesharedispatch.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Offer accept/reject. Accept is where the Redis reservation's atomicity finally pays
 * off against durable state: the reservation token comparison in {@link #accept} is
 * what stops an accept on an already-expired-and-reissued reservation from succeeding
 * (invariant: "a stale accept after expiration must fail cleanly").
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final DispatchOfferRepository offerRepository;
    private final DispatchRequestRepository requestRepository;
    private final AssignmentRepository assignmentRepository;
    private final OutboxRepository outboxRepository;
    private final AgentReservationStore reservationStore;
    private final AgentOperationalStateStore stateStore;
    private final DispatchMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Duration freshnessWindow;

    public OfferService(DispatchOfferRepository offerRepository, DispatchRequestRepository requestRepository,
                         AssignmentRepository assignmentRepository, OutboxRepository outboxRepository,
                         AgentReservationStore reservationStore, AgentOperationalStateStore stateStore,
                         DispatchMetrics metrics, DispatchProperties properties,
                         PlatformTransactionManager transactionManager) {
        this.offerRepository = offerRepository;
        this.requestRepository = requestRepository;
        this.assignmentRepository = assignmentRepository;
        this.outboxRepository = outboxRepository;
        this.reservationStore = reservationStore;
        this.stateStore = stateStore;
        this.metrics = metrics;
        this.freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Assignment accept(OfferId offerId) {
        DispatchOffer offer = requireOffer(offerId);
        Instant now = Instant.now();

        if (offer.status() == OfferStatus.ACCEPTED) {
            return assignmentRepository.findByOfferId(offerId)
                    .orElseThrow(() -> new ConflictException("Accepted offer has no assignment: " + offerId));
        }
        if (offer.status() != OfferStatus.PENDING) {
            throw new ConflictException("Offer %s is not pending (status=%s)".formatted(offerId, offer.status()));
        }
        if (offer.isExpired(now)) {
            expireOfferBestEffort(offer);
            throw new ConflictException("Offer %s expired at %s".formatted(offerId, offer.expiresAt()));
        }
        AssignmentId assignmentId = AssignmentId.forOffer(offer.id());
        AgentOperationalStateStore.OccupyResult occupyResult = stateStore.consumeReservationAndMarkOccupied(
                offer.agentId(), offer.reservationToken(), assignmentId, freshnessWindow);
        if (occupyResult == AgentOperationalStateStore.OccupyResult.STALE_RESERVATION
                || occupyResult == AgentOperationalStateStore.OccupyResult.INELIGIBLE) {
            expireOfferBestEffort(offer);
            throw new ConflictException("Reservation for agent %s is no longer held".formatted(offer.agentId()));
        }

        Assignment assignment;
        try {
            assignment = transactionTemplate.execute(status -> {
                if (!offerRepository.transitionFromPending(offerId, OfferStatus.ACCEPTED)) {
                    return assignmentRepository.findByOfferId(offerId)
                            .orElseThrow(() -> new ConflictException(
                                    "Offer %s was already resolved by a concurrent request".formatted(offerId)));
                }
                DispatchRequest request = requestRepository.findById(offer.requestId())
                        .orElseThrow(() -> new NotFoundException("DispatchRequest", offer.requestId()));
                if (!requestRepository.transitionFromSearching(request.id(), DispatchRequestStatus.MATCHED,
                        Optional.of(offer.agentId()))) {
                    throw new ConflictException("DispatchRequest %s is no longer SEARCHING".formatted(request.id()));
                }

                Assignment created = new Assignment(assignmentId, request.id(), offer.id(),
                        request.requesterId(), offer.agentId(), AssignmentStatus.CREATED, 0L, Instant.now(),
                        Optional.empty(), Optional.empty());
                assignmentRepository.insert(created);
                outboxRepository.append("AssignmentCreated", created.id().value(), Map.of(
                        "assignmentId", created.id().value().toString(),
                        "requestId", request.id().value().toString(),
                        "agentId", offer.agentId().value().toString(),
                        "requesterId", request.requesterId().value().toString()));
                return created;
            });
        } catch (RuntimeException failure) {
            compensateOccupancy(offer, assignmentId);
            throw failure;
        }

        if (occupyResult == AgentOperationalStateStore.OccupyResult.ACQUIRED) {
            metrics.reservationReleased();
        }

        log.info("offer accepted offerId={} assignmentId={} agentId={}", offerId, assignment.id(), offer.agentId());
        return assignment;
    }

    public void reject(OfferId offerId) {
        DispatchOffer offer = requireOffer(offerId);
        if (offer.status() != OfferStatus.PENDING) {
            throw new ConflictException("Offer %s is not pending (status=%s)".formatted(offerId, offer.status()));
        }
        if (!offerRepository.transitionFromPending(offerId, OfferStatus.REJECTED)) {
            throw new ConflictException("Offer %s was already resolved by a concurrent request".formatted(offerId));
        }
        reservationStore.release(offer.agentId(), offer.reservationToken());
        metrics.reservationReleased();
        log.info("offer rejected offerId={} agentId={}", offerId, offer.agentId());
        // Known gap (documented in docs/DESIGN.md "Known gaps"): the MVP does not
        // automatically re-run matching against the next candidate after a reject. A
        // production system would resubmit the request to MatchingService here.
    }

    private void expireOfferBestEffort(DispatchOffer offer) {
        offerRepository.transitionFromPending(offer.id(), OfferStatus.EXPIRED);
        reservationStore.release(offer.agentId(), offer.reservationToken());
        metrics.reservationReleased();
    }

    private void compensateOccupancy(DispatchOffer offer, AssignmentId assignmentId) {
        try {
            if (assignmentRepository.findByOfferId(offer.id()).isEmpty()) {
                stateStore.markAvailableIfOwned(offer.agentId(), assignmentId, freshnessWindow);
            }
        } catch (RuntimeException compensationFailure) {
            log.error("failed to compensate provisional occupancy offerId={} assignmentId={}",
                    offer.id(), assignmentId, compensationFailure);
        }
    }

    private DispatchOffer requireOffer(OfferId id) {
        return offerRepository.findById(id).orElseThrow(() -> new NotFoundException("DispatchOffer", id));
    }
}
