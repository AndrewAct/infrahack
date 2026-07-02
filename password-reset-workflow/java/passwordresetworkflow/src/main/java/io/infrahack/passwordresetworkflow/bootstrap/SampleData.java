package io.infrahack.passwordresetworkflow.bootstrap;

import io.infrahack.passwordresetworkflow.model.User;
import io.infrahack.passwordresetworkflow.repository.UserRepository;
import io.infrahack.passwordresetworkflow.util.PasswordHasher;

/** Demo user for in-memory mode; db/seed.sql inserts the same account for Postgres mode. */
public final class SampleData {

    public static final String DEMO_EMAIL = "ada@example.com";
    public static final String DEMO_PASSWORD = "correct-horse-battery";

    private SampleData() {}

    public static void seed(UserRepository users) {
        users.save(new User(DEMO_EMAIL, PasswordHasher.hash(DEMO_PASSWORD)));
    }
}
