package io.infrahack.passwordresetworkflow.web;

import java.util.Map;

import io.infrahack.passwordresetworkflow.service.PasswordResetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three-step reset API. Payload records are nested here on purpose: they are HTTP DTOs,
 * distinct from the stored domain object {@code model.PasswordResetRequest}.
 */
@RestController
@RequestMapping("/password-reset")
public class PasswordResetController {

    record RequestPayload(String email) {}

    record VerifyPayload(String email, String code) {}

    record ResetPayload(String email, String code, String newPassword) {}

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** 202: the code is produced and (in a real system) sent out-of-band; nothing to return. */
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> request(@RequestBody RequestPayload payload) {
        service.requestReset(payload.email());
        return Map.of("status", "code_sent");
    }

    @PostMapping("/verify")
    public Map<String, String> verify(@RequestBody VerifyPayload payload) {
        service.verify(payload.email(), payload.code());
        return Map.of("status", "verified");
    }

    @PostMapping("/reset")
    public Map<String, String> reset(@RequestBody ResetPayload payload) {
        service.resetPassword(payload.email(), payload.code(), payload.newPassword());
        return Map.of("status", "password_reset");
    }
}
