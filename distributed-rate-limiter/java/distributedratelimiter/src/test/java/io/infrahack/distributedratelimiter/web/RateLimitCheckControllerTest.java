package io.infrahack.distributedratelimiter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import io.infrahack.distributedratelimiter.DistributedratelimiterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP contract tests on a random embedded Tomcat port, against the default in-memory rule set
 * (no Postgres/Redis required), so this suite runs with zero external dependencies.
 */
@SpringBootTest(classes = DistributedratelimiterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitCheckControllerTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void allowsUpToTheFreeTierLimitThenRejects() {
        Map<String, Object> payload = Map.of("userId", "http-user-1", "tier", "free", "endpoint", "e");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = post("/v1/rate-limit/check", payload);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().contains("\"allowed\":true"),
                    "request " + i + " should be within the free tier's 5 rps");
        }

        ResponseEntity<String> sixth = post("/v1/rate-limit/check", payload);
        assertTrue(sixth.getBody().contains("\"allowed\":false"),
                "6th request should exceed the free tier's 5 rps burst cap");
        assertTrue(sixth.getBody().contains("user-free-rps"));
    }

    @Test
    void demoEndpointReturns429WithHeadersOnceRateLimited() {
        // TestRestTemplate requests all arrive from the same loopback address, so repeated hits
        // eventually trip the per-IP anonymous rule (10 steady + 20 burst cap).
        ResponseEntity<String> last = null;
        for (int i = 0; i < 40; i++) {
            last = rest.getForEntity("/demo/ping", String.class);
            if (last.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last.getStatusCode());
        assertTrue(last.getHeaders().containsKey("Retry-After"));
        assertTrue(last.getHeaders().containsKey("X-RateLimit-Limit"));
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
