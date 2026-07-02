package io.infrahack.passwordresetworkflow.service;

import java.time.Clock;
import java.time.Duration;

import io.infrahack.passwordresetworkflow.exception.ExpiredCodeException;
import io.infrahack.passwordresetworkflow.exception.InvalidCodeException;
import io.infrahack.passwordresetworkflow.exception.NoActiveResetRequestException;
import io.infrahack.passwordresetworkflow.exception.UserNotFoundException;
import io.infrahack.passwordresetworkflow.model.PasswordResetRequest;
import io.infrahack.passwordresetworkflow.model.User;
import io.infrahack.passwordresetworkflow.observability.Metrics;
import io.infrahack.passwordresetworkflow.repository.PasswordResetRequestRepository;
import io.infrahack.passwordresetworkflow.repository.UserRepository;
import io.infrahack.passwordresetworkflow.util.CodeGenerator;
import io.infrahack.passwordresetworkflow.util.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three-step reset flow.
 *
 * <ul>
 *   <li><b>Request</b> — generate a one-time 6-digit code for an existing user and store it with
 *       its generation time. Delivery is simulated by logging the code (a real system would email
 *       or SMS it).</li>
 *   <li><b>Verify</b> — a submitted code is accepted only if it matches the stored code and was
 *       generated no more than {@code codeTtlSeconds} seconds ago (inclusive at the boundary).
 *       Missing request, mismatched code, and expired code are distinct failures.</li>
 *   <li><b>Reset</b> — after verification the new password is hashed and persisted to the user's
 *       profile, and the code is deleted (single-use).</li>
 * </ul>
 *
 * <p>Time comes from the injected {@link Clock} so tests can pin and advance it deterministically
 * instead of sleeping through the expiry window.
 */
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final PasswordResetRequestRepository requests;
    private final CodeGenerator codeGenerator = new CodeGenerator();
    private final Clock clock;
    private final long codeTtlSeconds;
    private final Metrics metrics;

    public PasswordResetService(UserRepository users,
                                PasswordResetRequestRepository requests,
                                Clock clock,
                                long codeTtlSeconds,
                                Metrics metrics) {
        this.users = users;
        this.requests = requests;
        this.clock = clock;
        this.codeTtlSeconds = codeTtlSeconds;
        this.metrics = metrics;
    }

    /** Step 1: create and store a one-time code for the user. */
    public void requestReset(String email) {
        User user = requireUser(email);
        String code = codeGenerator.generate();
        PasswordResetRequest request = new PasswordResetRequest(user.email(), code, clock.instant());
        requests.save(request);
        metrics.countOutcome("request", "ok");
        log.info("reset code stored for {} (delivery simulated: code={})", user.email(), request.code());
    }

    /** Step 2: check the submitted code against the stored request. */
    public void verify(String email, String submittedCode) {
        requireText(email, "email");
        requireText(submittedCode, "code");
        PasswordResetRequest request = requests.findByEmail(email)
                .orElseThrow(() -> {
                    metrics.countOutcome("verify", "no_active_request");
                    return new NoActiveResetRequestException(email);
                });
        if (!submittedCode.equals(request.code())) {
            metrics.countOutcome("verify", "invalid_code");
            log.info("verify rejected for {}: code mismatch", email);
            throw new InvalidCodeException();
        }
        // Ensure the verify method also check the TTL logic
        long ageSeconds = Duration.between(request.generatedAt(), clock.instant()).getSeconds();
        if (ageSeconds > codeTtlSeconds) {
            metrics.countOutcome("verify", "expired_code");
            log.info("verify rejected for {}: code expired", email);
            throw new ExpiredCodeException();
        }
        metrics.countOutcome("verify", "ok");
        log.info("verify passed for {}", email);
    }

    /** Step 3: verify again, then persist the new password and invalidate the code. */
    public void resetPassword(String email, String submittedCode, String newPassword) {
        requirePasswordPolicy(newPassword);
        verify(email, submittedCode);
        String newHash = PasswordHasher.hash(newPassword);
        requests.delete(email);
        // Update the user's password with newHash
        users.updatePassword(email, newHash);
        metrics.countOutcome("reset", "ok");
        log.info("password reset completed for {}", email);
    }

    /** Used by login to prove (or disprove) that a reset actually persisted. */
    public boolean authenticate(String email, String rawPassword) {
        if (email == null || email.isBlank() || rawPassword == null) {
            return false;
        }
        return users.findByEmail(email)
                .map(user -> PasswordHasher.matches(rawPassword, user.passwordHash()))
                .orElse(false);
    }

    private User requireUser(String email) {
        requireText(email, "email");
        return users.findByEmail(email).orElseThrow(() -> {
            metrics.countOutcome("request", "user_not_found");
            return new UserNotFoundException(email);
        });
    }

    private static void requirePasswordPolicy(String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "newPassword must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
