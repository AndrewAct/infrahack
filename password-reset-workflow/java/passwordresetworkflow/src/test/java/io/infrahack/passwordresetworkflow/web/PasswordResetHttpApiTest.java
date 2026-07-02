package io.infrahack.passwordresetworkflow.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.Map;

import io.infrahack.passwordresetworkflow.PasswordResetWorkflowApplication;
import io.infrahack.passwordresetworkflow.bootstrap.SampleData;
import io.infrahack.passwordresetworkflow.model.PasswordResetRequest;
import io.infrahack.passwordresetworkflow.repository.PasswordResetRequestRepository;
import io.infrahack.passwordresetworkflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP contract tests on a random embedded Tomcat port. The test peeks the generated code out
 * of the repository — standing in for the out-of-band email/SMS channel a real system would use.
 */
@SpringBootTest(classes = PasswordResetWorkflowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordResetHttpApiTest {

    private static final String EMAIL = SampleData.DEMO_EMAIL;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordResetRequestRepository requests;

    @BeforeEach
    void resetState() {
        requests.delete(EMAIL);
        SampleData.seed(users);
    }

    @Test
    void fullFlow_requestVerifyReset_thenLoginWithNewPassword() {
        ResponseEntity<String> requested = post("/password-reset/request", Map.of("email", EMAIL));
        assertEquals(202, requested.getStatusCode().value());

        PasswordResetRequest stored = requests.findByEmail(EMAIL).orElseThrow(
                () -> new AssertionError("request step must store a pending reset request"));
        assertNotNull(stored.code(), "request step must generate a verification code");
        String code = stored.code();

        ResponseEntity<String> verified = post("/password-reset/verify",
                Map.of("email", EMAIL, "code", code));
        assertEquals(200, verified.getStatusCode().value());

        ResponseEntity<String> reset = post("/password-reset/reset",
                Map.of("email", EMAIL, "code", code, "newPassword", "brand-new-password"));
        assertEquals(200, reset.getStatusCode().value());

        ResponseEntity<String> newLogin = post("/auth/login",
                Map.of("email", EMAIL, "password", "brand-new-password"));
        assertEquals(200, newLogin.getStatusCode().value(),
                "login with the new password proves the reset was persisted");

        ResponseEntity<String> oldLogin = post("/auth/login",
                Map.of("email", EMAIL, "password", SampleData.DEMO_PASSWORD));
        assertEquals(401, oldLogin.getStatusCode().value(), "old password must stop working");
    }

    @Test
    void verify_wrongCode_returns400() {
        requests.save(new PasswordResetRequest(EMAIL, "123456", Instant.now()));

        ResponseEntity<String> res = post("/password-reset/verify",
                Map.of("email", EMAIL, "code", "999999"));

        assertEquals(400, res.getStatusCode().value());
    }

    @Test
    void verify_expiredCode_returns410() {
        requests.save(new PasswordResetRequest(EMAIL, "123456", Instant.now().minusSeconds(120)));

        ResponseEntity<String> res = post("/password-reset/verify",
                Map.of("email", EMAIL, "code", "123456"));

        assertEquals(410, res.getStatusCode().value());
    }

    @Test
    void verify_withoutRequest_returns404() {
        ResponseEntity<String> res = post("/password-reset/verify",
                Map.of("email", EMAIL, "code", "123456"));

        assertEquals(404, res.getStatusCode().value());
    }

    @Test
    void request_unknownEmail_returns404() {
        ResponseEntity<String> res = post("/password-reset/request",
                Map.of("email", "nobody@example.com"));

        assertEquals(404, res.getStatusCode().value());
    }

    private ResponseEntity<String> post(String path, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
