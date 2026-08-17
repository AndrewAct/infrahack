package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.domain.AssignmentStatus;
import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.domain.exception.NotFoundException;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore;
import io.infrahack.ridesharedispatch.observability.DispatchMetrics;
import io.infrahack.ridesharedispatch.repository.AssignmentRepository;
import io.infrahack.ridesharedispatch.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.time.Duration;

/**
 * CREATED -&gt; IN_PROGRESS -&gt; COMPLETED, guarded by optimistic concurrency control
 * (see AssignmentRepository.transitionVersion). Completion demonstrates the
 * transactional outbox: the status flip and the {@code AssignmentCompleted} event are
 * one Postgres transaction, so Kafka being down at that instant cannot lose the event --
 * it will simply be published a little later by OutboxPublisher. Payment is triggered
 * by that event asynchronously (see infrastructure.kafka.PaymentEventConsumer), never
 * inline here -- assignment completion must not block on, or depend on the success of,
 * payment processing.
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    private final AssignmentRepository assignmentRepository;
    private final OutboxRepository outboxRepository;
    private final AgentOperationalStateStore stateStore;
    private final DispatchMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Duration freshnessWindow;

    public AssignmentService(AssignmentRepository assignmentRepository, OutboxRepository outboxRepository,
                              AgentOperationalStateStore stateStore, DispatchMetrics metrics,
                              DispatchProperties properties, PlatformTransactionManager transactionManager) {
        this.assignmentRepository = assignmentRepository;
        this.outboxRepository = outboxRepository;
        this.stateStore = stateStore;
        this.metrics = metrics;
        this.freshnessWindow = Duration.ofSeconds(properties.locationFreshnessSeconds());
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Assignment start(AssignmentId id) {
        Assignment current = requireAssignment(id);
        if (!current.status().canTransitionTo(AssignmentStatus.IN_PROGRESS)) {
            throw new ConflictException(
                    "Assignment %s cannot start from status %s".formatted(id, current.status()));
        }

        boolean committed = transactionTemplate.execute(status -> {
            boolean ok = assignmentRepository.transitionVersion(id, AssignmentStatus.IN_PROGRESS, current.version());
            if (ok) {
                outboxRepository.append("AssignmentStarted", id.value(), Map.of("assignmentId", id.value().toString()));
            }
            return ok;
        });

        if (!committed) {
            throw new ConflictException(
                    "Assignment %s was concurrently modified (expected version %d)".formatted(id, current.version()));
        }
        return requireAssignment(id);
    }

    public Assignment complete(AssignmentId id) {
        Assignment current = requireAssignment(id);
        if (current.status() == AssignmentStatus.COMPLETED) {
            repairAvailabilityBestEffort(current);
            return current;
        }
        if (!current.status().canTransitionTo(AssignmentStatus.COMPLETED)) {
            throw new ConflictException(
                    "Assignment %s cannot complete from status %s".formatted(id, current.status()));
        }

        boolean committed = transactionTemplate.execute(status -> {
            boolean ok = assignmentRepository.transitionVersion(id, AssignmentStatus.COMPLETED, current.version());
            if (ok) {
                outboxRepository.append("AssignmentCompleted", id.value(), Map.of(
                        "assignmentId", id.value().toString(),
                        "requestId", current.requestId().value().toString(),
                        "agentId", current.agentId().value().toString(),
                        "requesterId", current.requesterId().value().toString()));
            }
            return ok;
        });

        if (!committed) {
            throw new ConflictException(
                    "Assignment %s was concurrently modified (expected version %d)".formatted(id, current.version()));
        }

        metrics.assignmentCompletedTotal().increment();
        repairAvailabilityBestEffort(current);
        log.info("assignment completed assignmentId={} agentId={}", id, current.agentId());
        return requireAssignment(id);
    }

    public Assignment requireAssignment(AssignmentId id) {
        return assignmentRepository.findById(id).orElseThrow(() -> new NotFoundException("Assignment", id));
    }

    private void repairAvailabilityBestEffort(Assignment assignment) {
        try {
            stateStore.markAvailableIfOwned(
                    assignment.agentId(), assignment.id(), freshnessWindow);
        } catch (RuntimeException redisFailure) {
            // Durable completion and its outbox event already committed. Keeping the
            // agent OCCUPIED is the safe failure mode; a duplicate complete retries this
            // repair without duplicating durable effects.
            log.warn("could not release completed agent assignmentId={} agentId={}",
                    assignment.id(), assignment.agentId(), redisFailure);
        }
    }
}
