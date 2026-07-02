package io.infrahack.passwordresetworkflow.repository;

import java.util.Optional;

import io.infrahack.passwordresetworkflow.model.PasswordResetRequest;

/**
 * Pending reset requests, keyed by email (at most one active request per user; saving replaces).
 * Codes live at most 30 seconds, so this stays in-process even when users are in Postgres —
 * a restart merely voids in-flight codes, which the user can re-request. Production analogue:
 * Redis with a TTL, or a DB row with an {@code expires_at} column.
 */
public interface PasswordResetRequestRepository {

    void save(PasswordResetRequest request);

    Optional<PasswordResetRequest> findByEmail(String email);

    void delete(String email);
}
