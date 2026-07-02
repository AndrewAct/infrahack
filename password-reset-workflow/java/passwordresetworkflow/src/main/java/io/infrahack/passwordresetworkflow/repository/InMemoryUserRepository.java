package io.infrahack.passwordresetworkflow.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.infrahack.passwordresetworkflow.model.User;

/** Thread-safe in-memory user store; the default when no DB_URL is configured. */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> byEmail = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(byEmail.get(email));
    }

    @Override
    public void save(User user) {
        byEmail.put(user.email(), user);
    }

    @Override
    public boolean updatePassword(String email, String newPasswordHash) {
        // computeIfPresent is atomic per key: no lost update if two resets race on the same user.
        return byEmail.computeIfPresent(email, (e, u) -> new User(e, newPasswordHash)) != null;
    }
}
