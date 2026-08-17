package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentStatus;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.service.AssignmentService;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant #9: one OCC version can have at most one successful writer. Also covers
 * invariant #6 (completion writes the outbox row durably) and the "duplicate
 * completion attempt" required test.
 */
class AssignmentServiceOccTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchRequestService dispatchRequestService;

    @Autowired
    private OfferService offerService;

    @Autowired
    private AssignmentService assignmentService;

    private Assignment createAssignment() {
        createAvailableAgentAt(new GeoPoint(37.7750, -122.4195));
        DispatchRequestService.CreateCommand command = new DispatchRequestService.CreateCommand(
                "STANDARD", new GeoPoint(37.7749, -122.4194), new GeoPoint(37.7849, -122.4094));
        DispatchRequestService.CreateResult result = dispatchRequestService.createOrReplay(
                RequesterId.newId(), "key-" + UUID.randomUUID(), command);
        return offerService.accept(result.offer().orElseThrow().id());
    }

    @Test
    void startThenCompleteWritesExactlyOneCompletionOutboxEvent() {
        Assignment created = createAssignment();

        Assignment started = assignmentService.start(created.id());
        assertThat(started.status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
        assertThat(started.version()).isEqualTo(1L);

        Assignment completed = assignmentService.complete(created.id());
        assertThat(completed.status()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(completed.version()).isEqualTo(2L);

        Integer outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'AssignmentCompleted' AND aggregate_id = ?",
                Integer.class, created.id().value());
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void duplicateCompletionRepairsHotStateWithoutDuplicateEffects() {
        Assignment created = createAssignment();
        assignmentService.start(created.id());
        assignmentService.complete(created.id());

        Assignment replay = assignmentService.complete(created.id());
        assertThat(replay.status()).isEqualTo(AssignmentStatus.COMPLETED);

        Integer outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'AssignmentCompleted' AND aggregate_id = ?",
                Integer.class, created.id().value());
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void cannotCompleteBeforeStarting() {
        Assignment created = createAssignment();

        assertThatThrownBy(() -> assignmentService.complete(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void concurrentStartAttemptsHaveExactlyOneWinner() throws Exception {
        Assignment created = createAssignment();
        int racers = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);

        List<Callable<Boolean>> attempts = IntStream.range(0, racers)
                .<Callable<Boolean>>mapToObj(i -> () -> {
                    startGate.await();
                    try {
                        assignmentService.start(created.id());
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

        assertThat(successes.get()).isEqualTo(1);
        Assignment finalState = assignmentService.requireAssignment(created.id());
        assertThat(finalState.version()).isEqualTo(1L);
        assertThat(finalState.status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
    }
}
