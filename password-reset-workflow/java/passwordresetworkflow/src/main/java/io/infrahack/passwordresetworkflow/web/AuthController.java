package io.infrahack.passwordresetworkflow.web;

import java.util.Map;

import io.infrahack.passwordresetworkflow.service.PasswordResetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Minimal login endpoint — the end-to-end proof that a reset password was actually persisted. */
@RestController
public class AuthController {

    record LoginPayload(String email, String password) {}

    private final PasswordResetService service;

    public AuthController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Object> login(@RequestBody LoginPayload payload) {
        if (service.authenticate(payload.email(), payload.password())) {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        // One generic 401 for wrong email and wrong password alike: no account enumeration.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorEnvelope.of("invalid_credentials", "Email or password is incorrect"));
    }
}
