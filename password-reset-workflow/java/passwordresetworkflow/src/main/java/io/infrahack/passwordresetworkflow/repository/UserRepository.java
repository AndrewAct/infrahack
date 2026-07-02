package io.infrahack.passwordresetworkflow.repository;

import java.util.Optional;

import io.infrahack.passwordresetworkflow.model.User;

/** User profile persistence. In-memory by default, Postgres when DB_URL is configured. */
public interface UserRepository {

    Optional<User> findByEmail(String email);

    void save(User user);

    /** Persist a new password hash for the user. Returns false when the user does not exist. */
    boolean updatePassword(String email, String newPasswordHash);
}
