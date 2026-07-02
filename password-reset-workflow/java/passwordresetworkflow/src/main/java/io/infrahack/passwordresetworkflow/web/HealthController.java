package io.infrahack.passwordresetworkflow.web;

import java.util.Map;
import java.util.function.BooleanSupplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final BooleanSupplier readinessProbe;

    public HealthController(BooleanSupplier readinessProbe) {
        this.readinessProbe = readinessProbe;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean ready = readinessProbe.getAsBoolean();
        HttpStatus status = ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(Map.of("status", ready ? "UP" : "DOWN"));
    }
}
