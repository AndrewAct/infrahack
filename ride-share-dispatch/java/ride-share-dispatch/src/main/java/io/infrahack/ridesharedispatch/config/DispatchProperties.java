package io.infrahack.ridesharedispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Every bound in the system in one place, per docs/DESIGN.md "Backpressure / overload".
 * Nothing here is a magic number buried in a service class -- if a value needs to change
 * for a load test, it changes here.
 */
@ConfigurationProperties(prefix = "dispatch")
@Validated
public record DispatchProperties(
        @Min(1) @Max(300) int reservationTtlSeconds,
        @Min(1) @Max(3600) int locationFreshnessSeconds,
        @Min(1) @Max(300) int offerTtlSeconds,
        @Min(0) @Max(20) int maxCellSearchRings,
        @Min(1) @Max(100) int maxCandidates,
        @Min(10) @Max(30_000) int matchingTimeoutMs,
        @NotNull @Valid Outbox outbox,
        @NotNull @Valid Payment payment,
        @NotNull @Valid Kafka kafka
) {
    public DispatchProperties {
        if (offerTtlSeconds > reservationTtlSeconds) {
            throw new IllegalArgumentException("offer TTL must not exceed reservation TTL");
        }
    }
    public record Outbox(@Min(1) @Max(1000) int batchSize,
                         @Min(50) @Max(60_000) int pollIntervalMs,
                         @Min(1) @Max(300) int claimTtlSeconds) {
    }

    public record Payment(
            @DecimalMin("0.0") @DecimalMax("1.0") double simulatedTimeoutRate,
            @Min(100) @Max(300_000) int reconciliationIntervalMs,
            @Min(1) @Max(1000) int batchSize,
            @Min(1) @Max(100) int maxAttempts,
            @Min(1) @Max(3600) int baseBackoffSeconds,
            @Min(1) @Max(300) int claimTtlSeconds) {
    }

    public record Kafka(@Min(1) @Max(32) int consumerConcurrency,
                        @Min(0) @Max(20) int retryAttempts,
                        @Min(100) @Max(60_000) int retryBackoffMs) {
    }
}
