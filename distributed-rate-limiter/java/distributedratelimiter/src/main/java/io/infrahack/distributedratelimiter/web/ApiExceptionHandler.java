package io.infrahack.distributedratelimiter.web;

import io.infrahack.distributedratelimiter.exception.DomainException;
import io.infrahack.distributedratelimiter.exception.RateLimitExceededException;
import io.infrahack.distributedratelimiter.model.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps failures to stable JSON errors, attaching Retry-After/X-RateLimit-* headers on rejection. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorEnvelope> handleRateLimit(RateLimitExceededException e) {
        RateLimitDecision decision = e.decision();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                .header("X-RateLimit-Limit", Long.toString(decision.limit()))
                .header("X-RateLimit-Remaining", Long.toString(decision.remaining()))
                .body(ErrorEnvelope.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorEnvelope> handle(Throwable e) {
        HttpStatus status = statusFor(e);
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("Unhandled API error", e);
        }
        return ResponseEntity.status(status).body(bodyFor(e, status));
    }

    private static HttpStatus statusFor(Throwable e) {
        return switch (e) {
            case IllegalArgumentException ignored -> HttpStatus.BAD_REQUEST;
            case HttpMessageNotReadableException ignored -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ErrorEnvelope bodyFor(Throwable e, HttpStatus status) {
        if (e instanceof DomainException domain) {
            return ErrorEnvelope.of(domain.code(), domain.getMessage());
        }
        return switch (status) {
            case BAD_REQUEST -> ErrorEnvelope.of("bad_request", e.getMessage());
            default -> ErrorEnvelope.of("internal_error", "Unexpected error");
        };
    }
}
