package io.infrahack.ridesharedispatch.api;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.AssignmentId;
import io.infrahack.ridesharedispatch.service.AssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/assignments")
public class AssignmentController {

    public record AssignmentResponse(UUID assignmentId, UUID requestId, UUID requesterId, UUID agentId,
                                      String status, long version, Instant createdAt,
                                      Instant startedAt, Instant completedAt) {
        static AssignmentResponse from(Assignment a) {
            return new AssignmentResponse(a.id().value(), a.requestId().value(), a.requesterId().value(),
                    a.agentId().value(), a.status().name(), a.version(), a.createdAt(),
                    a.startedAt().orElse(null), a.completedAt().orElse(null));
        }
    }

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PostMapping("/{assignmentId}/start")
    public ResponseEntity<AssignmentResponse> start(@PathVariable UUID assignmentId) {
        Assignment assignment = assignmentService.start(AssignmentId.of(assignmentId));
        return ResponseEntity.ok(AssignmentResponse.from(assignment));
    }

    @PostMapping("/{assignmentId}/complete")
    public ResponseEntity<AssignmentResponse> complete(@PathVariable UUID assignmentId) {
        Assignment assignment = assignmentService.complete(AssignmentId.of(assignmentId));
        return ResponseEntity.ok(AssignmentResponse.from(assignment));
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<AssignmentResponse> get(@PathVariable UUID assignmentId) {
        Assignment assignment = assignmentService.requireAssignment(AssignmentId.of(assignmentId));
        return ResponseEntity.ok(AssignmentResponse.from(assignment));
    }
}
