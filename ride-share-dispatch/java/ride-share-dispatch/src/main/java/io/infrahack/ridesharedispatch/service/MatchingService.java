package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.AgentOperationalState;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentReservationStore;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The five-stage pipeline from docs/DESIGN.md "Matching path":
 * <pre>
 * spatial candidate generation -&gt; staleness/eligibility filter -&gt; ETA ranking
 *     -&gt; top-K -&gt; atomic reservation attempt (first success wins, losers try next)
 * </pre>
 * Candidate discovery (steps 1-4) is allowed to be eventually consistent -- the spatial
 * index or the Redis state hash might be a moment stale. Step 5 is what must be exactly
 * right, and it is: final eligibility validation and reservation acquisition are one
 * atomic Redis operation, so even if two requests rank the same top candidate first,
 * only one of them can win it.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final SpatialIndex spatialIndex;
    private final AgentOperationalStateStore stateStore;
    private final AgentReservationStore reservationStore;
    private final EtaEstimator etaEstimator;
    private final DispatchProperties properties;
    private final DispatchMetrics metrics;

    public MatchingService(SpatialIndex spatialIndex, AgentOperationalStateStore stateStore,
                            AgentReservationStore reservationStore, EtaEstimator etaEstimator,
                            DispatchProperties properties, DispatchMetrics metrics) {
        this.spatialIndex = spatialIndex;
        this.stateStore = stateStore;
        this.reservationStore = reservationStore;
        this.etaEstimator = etaEstimator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public record MatchOutcome(AgentId agentId, UUID reservationToken) {
    }

    private record RankedCandidate(AgentId agentId, GeoPoint location, double etaMinutes) {
    }

    public Optional<MatchOutcome> attemptMatch(DispatchRequest request) {
        return metrics.matchLatency().record(() -> attemptMatchInternal(request));
    }

    private Optional<MatchOutcome> attemptMatchInternal(DispatchRequest request) {
        metrics.matchingAttemptsTotal().increment();
        Instant now = Instant.now();
        Duration freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.matchingTimeoutMs());

        List<AgentId> candidateIds = spatialIndex.nearby(
                request.origin(), properties.maxCellSearchRings(), properties.maxCandidates());
        metrics.matchingCandidatesExamined().increment(candidateIds.size());

        if (deadlineExceeded(deadlineNanos)) {
            return timedOut(request);
        }

        List<RankedCandidate> ranked = rankEligibleCandidates(
                request.origin(), request.serviceType(), candidateIds, now, freshnessWindow, deadlineNanos);

        for (RankedCandidate candidate : ranked) {
            if (deadlineExceeded(deadlineNanos)) {
                return timedOut(request);
            }
            UUID token = UUID.randomUUID();
            Duration reservationTtl = Duration.ofSeconds(properties.reservationTtlSeconds());
            AgentReservationStore.ReserveResult result = reservationStore.tryReserveEligible(
                    candidate.agentId(), token, reservationTtl, Instant.now(), freshnessWindow, request.serviceType());
            if (result == AgentReservationStore.ReserveResult.ACQUIRED) {
                metrics.reservationAcquired();
                log.info("reservation acquired agentId={} requestId={} etaMinutes={}",
                        candidate.agentId(), request.id(), candidate.etaMinutes());
                return Optional.of(new MatchOutcome(candidate.agentId(), token));
            }
            if (result == AgentReservationStore.ReserveResult.CONFLICT) {
                metrics.matchingReservationConflictsTotal().increment();
                log.info("reservation conflict agentId={} requestId={}, trying next candidate",
                        candidate.agentId(), request.id());
            }
        }

        metrics.matchingFailuresTotal().increment();
        log.info("matching failed requestId={} candidatesExamined={} eligible={}",
                request.id(), candidateIds.size(), ranked.size());
        return Optional.empty();
    }

    private List<RankedCandidate> rankEligibleCandidates(GeoPoint origin, String serviceType,
                                                         List<AgentId> candidateIds,
                                                         Instant now, Duration freshnessWindow,
                                                         long deadlineNanos) {
        List<RankedCandidate> eligible = new ArrayList<>();
        for (AgentId agentId : candidateIds) {
            if (deadlineExceeded(deadlineNanos)) break;
            Optional<AgentOperationalState> state = stateStore.get(agentId);
            if (state.isEmpty() || !state.get().isMatchable(now, freshnessWindow, serviceType)) {
                log.debug("excluding non-matchable candidate agentId={}", agentId);
                if (state.isEmpty() || state.get().status() != io.infrahack.ridesharedispatch.domain.AgentOperationalStatus.AVAILABLE
                        || !state.get().isFresh(now, freshnessWindow)) {
                    spatialIndex.remove(agentId); // lazy cleanup for expired/offline cell members
                }
                continue;
            }
            GeoPoint location = state.get().location().orElseThrow();
            eligible.add(new RankedCandidate(agentId, location, etaEstimator.estimateEtaMinutes(origin, location)));
        }
        eligible.sort(Comparator.comparingDouble(RankedCandidate::etaMinutes));
        return eligible.size() > properties.maxCandidates()
                ? eligible.subList(0, properties.maxCandidates())
                : eligible;
    }

    private Optional<MatchOutcome> timedOut(DispatchRequest request) {
        metrics.matchingFailuresTotal().increment();
        metrics.matchingTimeoutsTotal().increment();
        log.warn("matching timed out requestId={} budgetMs={}", request.id(), properties.matchingTimeoutMs());
        return Optional.empty();
    }

    private static boolean deadlineExceeded(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }
}
