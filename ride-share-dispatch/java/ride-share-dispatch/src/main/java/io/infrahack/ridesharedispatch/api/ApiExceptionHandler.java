package io.infrahack.ridesharedispatch.api;

import io.infrahack.ridesharedispatch.domain.exception.ConflictException;
import io.infrahack.ridesharedispatch.domain.exception.DomainException;
import io.infrahack.ridesharedispatch.domain.exception.IdempotencyConflictException;
import io.infrahack.ridesharedispatch.domain.exception.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single place domain exceptions become HTTP status + body. Services stay
 * HTTP-agnostic; this is the only class that knows what a 404 vs 409 means.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorEnvelope> handleDomain(DomainException e) {
        HttpStatus status = switch (e) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case IdempotencyConflictException ignored -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(ErrorEnvelope.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ErrorEnvelope> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.of("bad_request", "Request validation failed"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintConflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorEnvelope.of("conflict", "The requested state conflicts with an existing resource"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception e) {
        log.error("unhandled API failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorEnvelope.of("internal_error", "The request could not be completed"));
    }
}
