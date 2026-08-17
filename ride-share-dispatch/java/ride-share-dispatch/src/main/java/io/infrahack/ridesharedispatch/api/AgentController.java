package io.infrahack.ridesharedispatch.api;

import io.infrahack.ridesharedispatch.domain.Agent;
import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.infrastructure.redis.AgentOperationalStateStore;
import io.infrahack.ridesharedispatch.service.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent registration (durable, rare) and hot pings (availability + location, frequent).
 * No authentication -- see module README "Out of scope".
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    public record RegisterAgentRequest(@NotBlank @Size(max = 120) String displayName,
                                       @NotBlank @Size(max = 40) String serviceType) {
    }

    public record AgentResponse(UUID agentId, String displayName, String serviceType, double rating,
                                 String accountStatus, Instant createdAt) {
        static AgentResponse from(Agent agent) {
            return new AgentResponse(agent.id().value(), agent.displayName(), agent.serviceType(),
                    agent.rating(), agent.accountStatus().name(), agent.createdAt());
        }
    }

    public record AvailabilityRequest(@NotNull Boolean available) {
    }

    public record LocationUpdateRequest(
                                         @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
                                         @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
                                         @PositiveOrZero long sequenceNumber,
                                         Instant clientTimestamp) {
    }

    public record LocationUpdateResponse(String result) {
    }

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> register(@Valid @RequestBody RegisterAgentRequest request) {
        Agent agent = agentService.register(request.displayName(), request.serviceType());
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentResponse.from(agent));
    }

    @PostMapping("/{agentId}/availability")
    public ResponseEntity<Void> setAvailability(@PathVariable UUID agentId,
                                                @Valid @RequestBody AvailabilityRequest request) {
        agentService.setAvailability(AgentId.of(agentId), request.available());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{agentId}/location")
    public ResponseEntity<LocationUpdateResponse> updateLocation(@PathVariable UUID agentId,
                                                                   @Valid @RequestBody LocationUpdateRequest request) {
        AgentOperationalStateStore.LocationUpdateResult result = agentService.recordLocation(
                AgentId.of(agentId), new GeoPoint(request.latitude(), request.longitude()),
                request.sequenceNumber(), request.clientTimestamp() == null ? Instant.now() : request.clientTimestamp());
        return ResponseEntity.ok(new LocationUpdateResponse(result.name()));
    }
}
