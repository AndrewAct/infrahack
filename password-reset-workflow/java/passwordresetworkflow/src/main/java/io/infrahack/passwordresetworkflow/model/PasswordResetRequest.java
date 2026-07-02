package io.infrahack.passwordresetworkflow.model;

import java.time.Instant;

/**
 * One pending password reset request: the one-time code the user must echo back and when it
 * was generated. {@code generatedAt} is a UTC {@link Instant} so the expiry comparison is
 * timezone-free. At most one request is active per email; a new request replaces the old one.
 */
public record PasswordResetRequest(String email, String code, Instant generatedAt) {}
