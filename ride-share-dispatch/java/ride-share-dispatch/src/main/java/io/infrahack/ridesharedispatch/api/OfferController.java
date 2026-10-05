package io.infrahack.ridesharedispatch.api;

import io.infrahack.ridesharedispatch.domain.Assignment;
import io.infrahack.ridesharedispatch.domain.OfferId;
import io.infrahack.ridesharedispatch.service.OfferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/offers")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    @PostMapping("/{offerId}/accept")
    public ResponseEntity<AssignmentController.AssignmentResponse> accept(@PathVariable UUID offerId) {
        Assignment assignment = offerService.accept(OfferId.of(offerId));
        return ResponseEntity.ok(AssignmentController.AssignmentResponse.from(assignment));
    }

    @PostMapping("/{offerId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID offerId) {
        offerService.reject(OfferId.of(offerId));
        return ResponseEntity.noContent().build();
    }
}
