package io.infrahack.ridesharedispatch.api;

import io.infrahack.ridesharedispatch.domain.DispatchOffer;
import io.infrahack.ridesharedispatch.domain.DispatchRequest;
import io.infrahack.ridesharedispatch.domain.DispatchRequestId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.domain.RequesterId;
import io.infrahack.ridesharedispatch.service.DispatchRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The idempotent command entry point (see DispatchRequestService javadoc). The
 * {@code Idempotency-Key} header, not any field in the JSON body, is what a client
 * retry must keep constant.
 */
@RestController
@RequestMapping("/dispatch-requests")
@Validated
public class DispatchRequestController {

    public record CreateDispatchRequestRequest(
            @NotNull UUID requesterId,
            @NotBlank @Size(max = 40) String serviceType,
            @DecimalMin("-90.0") @DecimalMax("90.0") double originLat,
            @DecimalMin("-180.0") @DecimalMax("180.0") double originLng,
            @DecimalMin("-90.0") @DecimalMax("90.0") double destLat,
            @DecimalMin("-180.0") @DecimalMax("180.0") double destLng) {
    }

    public record OfferSummary(UUID offerId, UUID agentId, String status, Instant expiresAt) {
        static OfferSummary from(DispatchOffer offer) {
            return new OfferSummary(offer.id().value(), offer.agentId().value(), offer.status().name(), offer.expiresAt());
        }
    }

    public record DispatchRequestResponse(UUID requestId, UUID requesterId, String status, String serviceType,
                                           double originLat, double originLng, double destLat, double destLng,
                                           UUID matchedAgentId, OfferSummary offer, Instant createdAt) {
        static DispatchRequestResponse from(DispatchRequest request, Optional<DispatchOffer> offer) {
            return new DispatchRequestResponse(
                    request.id().value(), request.requesterId().value(), request.status().name(), request.serviceType(),
                    request.origin().latitude(), request.origin().longitude(),
                    request.destination().latitude(), request.destination().longitude(),
                    request.matchedAgentId().map(io.infrahack.ridesharedispatch.domain.AgentId::value).orElse(null),
                    offer.map(OfferSummary::from).orElse(null),
                    request.createdAt());
        }
    }

    private final DispatchRequestService dispatchRequestService;

    public DispatchRequestController(DispatchRequestService dispatchRequestService) {
        this.dispatchRequestService = dispatchRequestService;
    }

    @PostMapping
    public ResponseEntity<DispatchRequestResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            @Valid @RequestBody CreateDispatchRequestRequest request) {
        DispatchRequestService.CreateCommand command = new DispatchRequestService.CreateCommand(
                request.serviceType(),
                new GeoPoint(request.originLat(), request.originLng()),
                new GeoPoint(request.destLat(), request.destLng()));

        DispatchRequestService.CreateResult result = dispatchRequestService.createOrReplay(
                RequesterId.of(request.requesterId()), idempotencyKey, command);

        HttpStatus status = result.wasReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(DispatchRequestResponse.from(result.request(), result.offer()));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<DispatchRequestResponse> get(@PathVariable UUID requestId) {
        DispatchRequestId id = DispatchRequestId.of(requestId);
        DispatchRequest request = dispatchRequestService.requireById(id);
        return ResponseEntity.ok(DispatchRequestResponse.from(request, dispatchRequestService.latestOfferFor(id)));
    }
}
