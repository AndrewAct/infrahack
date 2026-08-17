package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.DispatchRequestStatus;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentReservationStore;
import io.infrahack.ridesharedispatch.service.MatchingService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReservationConcurrencyTest extends AbstractIntegrationTest {

    private static final GeoPoint ORIGIN = new GeoPoint(37.7749, -122.4194);

    @Autowired private AgentReservationStore reservationStore;
    @Autowired private MatchingService matchingService;

    @RepeatedTest(5)
    void exactlyOneConcurrentDispatchRequestReservesTheOnlyDriver() throws Exception {
        createAvailableAgentAt(ORIGIN);
        int racerCount = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racerCount);
        List<Callable<Optional<MatchingService.MatchOutcome>>> racers = IntStream.range(0, racerCount)
                .<Callable<Optional<MatchingService.MatchOutcome>>>mapToObj(i -> () -> {
                    startGate.await();
                    return matchingService.attemptMatch(request());
                }).toList();
        List<Future<Optional<MatchingService.MatchOutcome>>> futures = racers.stream().map(pool::submit).toList();
        startGate.countDown();

        AtomicInteger winners = new AtomicInteger();
        for (Future<Optional<MatchingService.MatchOutcome>> future : futures) {
            if (future.get(5, TimeUnit.SECONDS).isPresent()) winners.incrementAndGet();
        }
        pool.shutdown();
        assertThat(winners).hasValue(1);
    }

    @Test
    void wrongTokenCannotReleaseTheWinningReservation() {
        AgentId agentId = createAvailableAgentAt(ORIGIN);
        UUID winner = UUID.randomUUID();
        assertThat(reservationStore.tryReserveEligible(agentId, winner, Duration.ofSeconds(10),
                Instant.now(), Duration.ofSeconds(5), "STANDARD"))
                .isEqualTo(AgentReservationStore.ReserveResult.ACQUIRED);

        assertThat(reservationStore.release(agentId, UUID.randomUUID())).isFalse();
        assertThat(redisTemplate.opsForValue().get("reservation:" + agentId.value()))
                .isEqualTo(winner.toString());
        assertThat(reservationStore.release(agentId, winner)).isTrue();
    }

    private static DispatchRequest request() {
        Instant now = Instant.now();
        return new DispatchRequest(DispatchRequestId.newId(), RequesterId.newId(), UUID.randomUUID().toString(), "fp",
                DispatchRequestStatus.SEARCHING, "STANDARD", ORIGIN, ORIGIN, Optional.empty(), now, now);
    }
}
