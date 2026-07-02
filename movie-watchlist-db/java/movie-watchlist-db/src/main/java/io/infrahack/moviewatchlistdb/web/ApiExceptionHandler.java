package io.infrahack.moviewatchlistdb.web;

import java.util.concurrent.CompletionException;

import io.infrahack.moviewatchlistdb.exception.DomainException;
import io.infrahack.moviewatchlistdb.exception.DuplicateMovieException;
import io.infrahack.moviewatchlistdb.exception.MovieNotFoundException;
import io.infrahack.moviewatchlistdb.exception.MovieNotInListException;
import io.infrahack.moviewatchlistdb.exception.WatchListNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorEnvelope> handle(Throwable thrown) {
        Throwable e = unwrap(thrown);
        HttpStatus status = statusFor(e);
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("Unhandled API error", e);
        }
        return ResponseEntity.status(status).body(bodyFor(e));
    }

    private static HttpStatus statusFor(Throwable e) {
        return switch (e) {
            case WatchListNotFoundException ignored -> HttpStatus.NOT_FOUND;
            case MovieNotFoundException ignored -> HttpStatus.NOT_FOUND;
            case MovieNotInListException ignored -> HttpStatus.NOT_FOUND;
            case DuplicateMovieException ignored -> HttpStatus.CONFLICT;
            case IllegalArgumentException ignored -> HttpStatus.BAD_REQUEST;
            case HttpMessageNotReadableException ignored -> HttpStatus.BAD_REQUEST;
            case MissingServletRequestParameterException ignored -> HttpStatus.BAD_REQUEST;
            case MethodArgumentTypeMismatchException ignored -> HttpStatus.BAD_REQUEST;
            case HttpRequestMethodNotSupportedException ignored -> HttpStatus.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException ignored -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ErrorEnvelope bodyFor(Throwable e) {
        if (e instanceof DomainException domain) {
            return ErrorEnvelope.of(domain.code(), domain.getMessage());
        }
        if (statusFor(e) == HttpStatus.BAD_REQUEST) {
            return ErrorEnvelope.of("bad_request", e.getMessage());
        }
        if (statusFor(e) == HttpStatus.METHOD_NOT_ALLOWED) {
            return ErrorEnvelope.of("method_not_allowed", e.getMessage());
        }
        if (statusFor(e) == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return ErrorEnvelope.of("unsupported_media_type", e.getMessage());
        }
        return ErrorEnvelope.of("internal_error", "Unexpected error");
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
