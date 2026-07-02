package io.infrahack.passwordresetworkflow.web;

import io.infrahack.passwordresetworkflow.exception.DomainException;
import io.infrahack.passwordresetworkflow.exception.ExpiredCodeException;
import io.infrahack.passwordresetworkflow.exception.InvalidCodeException;
import io.infrahack.passwordresetworkflow.exception.NoActiveResetRequestException;
import io.infrahack.passwordresetworkflow.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to stable JSON errors. The three verify failures are deliberately distinct:
 * 404 no active request, 400 wrong code, 410 expired code — so a client (and a test) can tell
 * "never asked", "typo", and "too slow" apart.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
            case UserNotFoundException ignored -> HttpStatus.NOT_FOUND;
            case NoActiveResetRequestException ignored -> HttpStatus.NOT_FOUND;
            case InvalidCodeException ignored -> HttpStatus.BAD_REQUEST;
            case ExpiredCodeException ignored -> HttpStatus.GONE;
            case IllegalArgumentException ignored -> HttpStatus.BAD_REQUEST;
            case HttpMessageNotReadableException ignored -> HttpStatus.BAD_REQUEST;
            case HttpRequestMethodNotSupportedException ignored -> HttpStatus.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException ignored -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ErrorEnvelope bodyFor(Throwable e, HttpStatus status) {
        if (e instanceof DomainException domain) {
            return ErrorEnvelope.of(domain.code(), domain.getMessage());
        }
        return switch (status) {
            case BAD_REQUEST -> ErrorEnvelope.of("bad_request", e.getMessage());
            case METHOD_NOT_ALLOWED -> ErrorEnvelope.of("method_not_allowed", e.getMessage());
            case UNSUPPORTED_MEDIA_TYPE -> ErrorEnvelope.of("unsupported_media_type", e.getMessage());
            default -> ErrorEnvelope.of("internal_error", "Unexpected error");
        };
    }
}
