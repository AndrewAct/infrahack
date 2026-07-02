package io.infrahack.passwordresetworkflow.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.infrahack.passwordresetworkflow.model.PasswordResetRequest;

/** Thread-safe in-process store for pending reset requests (ephemeral by design; see interface). */
public final class InMemoryPasswordResetRequestRepository implements PasswordResetRequestRepository {

    private final Map<String, PasswordResetRequest> byEmail = new ConcurrentHashMap<>();

    @Override
    public void save(PasswordResetRequest request) {
        byEmail.put(request.email(), request);
    }

    @Override
    public Optional<PasswordResetRequest> findByEmail(String email) {
        return Optional.ofNullable(byEmail.get(email));
    }

    @Override
    public void delete(String email) {
        byEmail.remove(email);
    }
}
