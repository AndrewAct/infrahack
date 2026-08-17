package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentStatus;
import io.infrahack.ridesharedispatch.domain.DispatchOffer;
import io.infrahack.ridesharedispatch.domain.DispatchRequestStatus;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.OfferId;
import io.infrahack.ridesharedispatch.domain.OfferStatus;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import io.infrahack.ridesharedispatch.service.OfferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OfferServiceTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchRequestService dispatchRequestService;

    @Autowired
    private OfferService offerService;

    private DispatchOffer createRequestWithOffer() {
        createAvailableDriverAt(new GeoPoint(37.7750, -122.4195));
        DispatchRequestService.CreateCommand command = new DispatchRequestService.CreateCommand(
                "STANDARD", new GeoPoint(37.7749, -122.4194), new GeoPoint(37.7849, -122.4094));
        DispatchRequestService.CreateResult result = dispatchRequestService.createOrReplay(
                RequesterId.newId(), "key-" + UUID.randomUUID(), command);
        assertThat(result.offer()).as("matching should have produced an offer").isPresent();
        return result.offer().orElseThrow();
    }

    @Test
    void acceptingOfferCreatesAssignmentAndMarksRequestMatched() {
        DispatchOffer offer = createRequestWithOffer();

        Assignment assignment = offerService.accept(offer.id());

        assertThat(assignment.status()).isEqualTo(AssignmentStatus.CREATED);
        assertThat(assignment.driverId()).isEqualTo(offer.driverId());

        String requestStatus = jdbc.queryForObject(
                "SELECT status FROM dispatch_requests WHERE request_id = ?", String.class, offer.requestId().value());
        assertThat(requestStatus).isEqualTo(DispatchRequestStatus.MATCHED.name());
    }

    @Test
    void expiredOfferCannotBeAcceptedAndReleasesReservation() {
        DispatchOffer offer = createRequestWithOffer();

        // application.yml (test) sets offer-ttl-seconds: 2
        await().atMost(5, TimeUnit.SECONDS).until(() -> offer.isExpired(java.time.Instant.now()));

        assertThatThrownBy(() -> offerService.accept(offer.id())).isInstanceOf(ConflictException.class);

        String status = jdbc.queryForObject(
                "SELECT status FROM dispatch_offers WHERE offer_id = ?", String.class, offer.id().value());
        assertThat(status).isEqualTo(OfferStatus.EXPIRED.name());
    }

    @Test
    void concurrentAcceptAttemptsHaveExactlyOneWinner() throws Exception {
        DispatchOffer offer = createRequestWithOffer();
        int racers = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);

        List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, racers)
                .<Callable<Boolean>>mapToObj(i -> () -> {
                    startGate.await();
                    try {
                        offerService.accept(offer.id());
                        return true;
                    } catch (ConflictException e) {
                        return false;
                    }
                }).collect(Collectors.toList());

        List<Future<Boolean>> futures = attempts.stream().map(pool::submit).collect(Collectors.toList());
        startGate.countDown();

        AtomicInteger successes = new AtomicInteger();
        for (Future<Boolean> f : futures) {
            if (f.get(5, TimeUnit.SECONDS)) {
                successes.incrementAndGet();
            }
        }
        pool.shutdown();

        assertThat(successes.get()).isGreaterThanOrEqualTo(1);

        Integer assignmentCount = jdbc.queryForObject(
                "SELECT count(*) FROM assignments WHERE offer_id = ?", Integer.class, offer.id().value());
        assertThat(assignmentCount).isEqualTo(1);
    }

    @Test
    void rejectingOfferReleasesReservation() {
        DispatchOffer offer = createRequestWithOffer();

        offerService.reject(offer.id());

        String status = jdbc.queryForObject(
                "SELECT status FROM dispatch_offers WHERE offer_id = ?", String.class, offer.id().value());
        assertThat(status).isEqualTo(OfferStatus.REJECTED.name());
        assertThat(redisTemplate.hasKey("reservation:" + offer.driverId().value())).isFalse();
    }

    @Test
    void acceptOnAlreadyResolvedOfferFailsCleanly() {
        DispatchOffer offer = createRequestWithOffer();
        offerService.reject(offer.id());

        assertThatThrownBy(() -> offerService.accept(offer.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void staleOfferCannotDeleteAReservationReissuedToAnotherRequest() {
        DispatchOffer offer = createRequestWithOffer();
        String key = "reservation:" + offer.driverId().value();
        UUID replacement = UUID.randomUUID();
        redisTemplate.opsForValue().set(key, replacement.toString(), java.time.Duration.ofSeconds(10));

        assertThatThrownBy(() -> offerService.accept(offer.id())).isInstanceOf(ConflictException.class);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(replacement.toString());
    }

    @Test
    void driverThatBecameStaleAfterReservationCannotBeAccepted() {
        DispatchOffer offer = createRequestWithOffer();
        redisTemplate.opsForHash().put("driver:state:" + offer.driverId().value(), "lastSeen",
                Long.toString(java.time.Instant.now().minusSeconds(30).toEpochMilli()));

        assertThatThrownBy(() -> offerService.accept(offer.id())).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM assignments WHERE offer_id = ?", Integer.class, offer.id().value()))
                .isZero();
        assertThat(redisTemplate.hasKey("reservation:" + offer.driverId().value())).isFalse();
    }
}
