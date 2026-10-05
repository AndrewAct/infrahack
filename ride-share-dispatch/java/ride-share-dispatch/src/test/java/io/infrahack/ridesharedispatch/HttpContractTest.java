package io.infrahack.ridesharedispatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpContractTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void driverApiUsesRideShareVocabulary() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/drivers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"displayName":"Driver One","serviceType":"STANDARD"}
                        """))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"driverId\"");
    }

    @Test
    void validationErrorsUseTheStableEnvelope() throws Exception {
        String oversizedKey = "x".repeat(121);
        String body = """
                {"requesterId":"00000000-0000-0000-0000-000000000001",
                 "serviceType":"STANDARD","originLat":37.0,"originLng":-122.0,
                 "destLat":37.1,"destLng":-122.1}
                """;
        HttpRequest request = HttpRequest.newBuilder(uri("/dispatch-requests"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", oversizedKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"bad_request\"");
    }

    @Test
    void unauthenticatedHealthDoesNotExposeComponentDetails() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
