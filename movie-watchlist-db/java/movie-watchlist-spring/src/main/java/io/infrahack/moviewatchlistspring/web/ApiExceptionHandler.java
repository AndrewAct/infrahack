package io.infrahack.moviewatchlistspring.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.infrahack.moviewatchlistspring.exception.DomainException;
import io.infrahack.moviewatchlistspring.exception.DuplicateMovieException;

/**
 * The single place where exceptions become HTTP status + error body (the Spring equivalent of the raw
 * module's {@code ApiExceptions}). Domain exceptions are HTTP-agnostic; the mapping lives only here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** All domain not-found variants -> 404; duplicate -> 409. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorEnvelope> handleDomain(DomainException e) {
        HttpStatus status = switch (e) {
            case DuplicateMovieException d -> HttpStatus.CONFLICT;
            default -> HttpStatus.NOT_FOUND; // WatchListNotFound, MovieNotFound, MovieNotInList
        };
        return ResponseEntity.status(status).body(ErrorEnvelope.of(e.code(), e.getMessage()));
    }

    /** Bad UUID in the path, malformed JSON, or a missing field -> 400. */
    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorEnvelope> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.of("bad_request", e.getMessage()));
    }
}
