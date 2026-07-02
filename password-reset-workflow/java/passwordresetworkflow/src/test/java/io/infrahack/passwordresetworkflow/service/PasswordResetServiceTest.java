package io.infrahack.passwordresetworkflow.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;

import io.infrahack.passwordresetworkflow.PasswordResetWorkflowApplication;
import io.infrahack.passwordresetworkflow.bootstrap.SampleData;
import io.infrahack.passwordresetworkflow.exception.ExpiredCodeException;
import io.infrahack.passwordresetworkflow.exception.InvalidCodeException;
import io.infrahack.passwordresetworkflow.exception.NoActiveResetRequestException;
import io.infrahack.passwordresetworkflow.exception.UserNotFoundException;
import io.infrahack.passwordresetworkflow.model.PasswordResetRequest;
import io.infrahack.passwordresetworkflow.repository.PasswordResetRequestRepository;
import io.infrahack.passwordresetworkflow.repository.UserRepository;
import io.infrahack.passwordresetworkflow.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Service-level tests. Each of the three flow steps is exercised in isolation: verify/reset
 * tests seed the stored request directly, so a failure points at exactly one root cause
 * instead of cascading from the request step.
 */
@SpringBootTest(classes = PasswordResetWorkflowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PasswordResetServiceTest.PinnedClock.class)
class PasswordResetServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-01T12:00:00Z");
    private static final String EMAIL = SampleData.DEMO_EMAIL;
    private static final String CODE = "123456";

    @TestConfiguration
    static class PinnedClock {
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private PasswordResetService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordResetRequestRepository requests;
    @Autowired
    private Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void resetState() {
        mutableClock().set(T0);
        requests.delete(EMAIL);
        SampleData.seed(users); // restore the original password after reset tests
    }

    // ---- Step 1: request ----

    @Test
    void requestReset_generatesAndStoresSixDigitCodeWithTimestamp() {
        service.requestReset(EMAIL);

        PasswordResetRequest stored = requests.findByEmail(EMAIL).orElseThrow(
                () -> new AssertionError("request step must store a pending reset request"));
        assertNotNull(stored.code(), "request step must generate a verification code");
        assertTrue(stored.code().matches("\\d{6}"), "code must be 6 digits, was: " + stored.code());
        assertEquals(T0, stored.generatedAt(), "generation time must be recorded from the clock");
    }

    @Test
    void requestReset_unknownEmail_throwsUserNotFound() {
        assertThrows(UserNotFoundException.class, () -> service.requestReset("nobody@example.com"));
    }

    // ---- Step 2: verify ----

    @Test
    void verify_correctCodeWithinWindow_passes() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));
        mutableClock().advanceSeconds(10);

        assertDoesNotThrow(() -> service.verify(EMAIL, CODE));
    }

    @Test
    void verify_atExactly30Seconds_stillPasses() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));
        mutableClock().advanceSeconds(30); // boundary is inclusive: age <= 30s is valid

        assertDoesNotThrow(() -> service.verify(EMAIL, CODE));
    }

    @Test
    void verify_after30Seconds_throwsExpiredCode() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));
        mutableClock().advanceSeconds(31);

        assertThrows(ExpiredCodeException.class, () -> service.verify(EMAIL, CODE),
                "a code older than 30s must be rejected as expired");
    }

    @Test
    void verify_wrongCode_throwsInvalidCode() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));

        assertThrows(InvalidCodeException.class, () -> service.verify(EMAIL, "999999"));
    }

    @Test
    void verify_withoutRequest_throwsNoActiveResetRequest() {
        assertThrows(NoActiveResetRequestException.class, () -> service.verify(EMAIL, CODE));
    }

    // ---- Step 3: reset ----

    @Test
    void resetPassword_persistsNewPasswordToProfile() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));

        service.resetPassword(EMAIL, CODE, "brand-new-password");

        assertTrue(service.authenticate(EMAIL, "brand-new-password"),
                "new password must be persisted to the user's profile");
        assertFalse(service.authenticate(EMAIL, SampleData.DEMO_PASSWORD),
                "old password must stop working after a reset");
    }

    @Test
    void resetPassword_codeIsSingleUse() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));

        service.resetPassword(EMAIL, CODE, "brand-new-password");

        assertThrows(NoActiveResetRequestException.class, () -> service.verify(EMAIL, CODE),
                "a used code must be invalidated");
    }

    @Test
    void resetPassword_expiredCode_rejectedAndPasswordUnchanged() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));
        mutableClock().advanceSeconds(31);

        assertThrows(ExpiredCodeException.class,
                () -> service.resetPassword(EMAIL, CODE, "brand-new-password"));
        assertTrue(service.authenticate(EMAIL, SampleData.DEMO_PASSWORD),
                "a rejected reset must leave the stored password untouched");
    }

    @Test
    void resetPassword_shortPassword_rejected() {
        requests.save(new PasswordResetRequest(EMAIL, CODE, T0));

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword(EMAIL, CODE, "short"));
    }
}
