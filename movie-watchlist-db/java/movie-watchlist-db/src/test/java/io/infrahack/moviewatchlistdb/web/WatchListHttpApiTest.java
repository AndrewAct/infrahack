package io.infrahack.moviewatchlistdb.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import io.infrahack.moviewatchlistdb.MovieWatchlistDbApplication;
import io.infrahack.moviewatchlistdb.bootstrap.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        classes = MovieWatchlistDbApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WatchListHttpApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final String sampleList = SampleData.SAMPLE_WATCHLIST.toString();
    private final String sampleMovie = SampleData.MOVIES.get(0).id().toString();

    @Test
    void addMovie_toExistingList_returns201WithLocation() {
        ResponseEntity<String> res = post("/watchlists/" + sampleList + "/movies",
                Map.of("movieId", sampleMovie));

        assertEquals(201, res.getStatusCode().value());
        assertNotNull(res.getHeaders().getLocation(), "201 should carry a Location header");
    }

    @Test
    void addMovie_duplicate_returns409() {
        post("/watchlists/" + sampleList + "/movies", Map.of("movieId", sampleMovie));
        ResponseEntity<String> dup = post("/watchlists/" + sampleList + "/movies",
                Map.of("movieId", sampleMovie));

        assertEquals(409, dup.getStatusCode().value());
    }

    @Test
    void removeMovie_present_returns204() {
        post("/watchlists/" + sampleList + "/movies", Map.of("movieId", sampleMovie));
        ResponseEntity<String> res = delete("/watchlists/" + sampleList + "/movies/" + sampleMovie);

        assertEquals(204, res.getStatusCode().value());
    }

    @Test
    void addMovie_missingList_returns404() {
        String missing = UUID.randomUUID().toString();
        ResponseEntity<String> res = post("/watchlists/" + missing + "/movies",
                Map.of("movieId", sampleMovie));

        assertEquals(404, res.getStatusCode().value());
    }

    @Test
    void removeMovie_missingList_returns404() {
        String missing = UUID.randomUUID().toString();
        ResponseEntity<String> res = delete("/watchlists/" + missing + "/movies/" + sampleMovie);

        assertEquals(404, res.getStatusCode().value());
    }

    @Test
    void removeMovie_notInList_returns404() {
        ResponseEntity<String> res = delete("/watchlists/" + sampleList + "/movies/" + sampleMovie);

        assertEquals(404, res.getStatusCode().value());
    }

    @Test
    void addMovie_unknownMovie_returns404() {
        String ghost = UUID.randomUUID().toString();
        ResponseEntity<String> res = post("/watchlists/" + sampleList + "/movies",
                Map.of("movieId", ghost));

        assertEquals(404, res.getStatusCode().value());
    }

    @Test
    void addMovie_badUuidInPath_returns400() {
        ResponseEntity<String> res = post("/watchlists/not-a-uuid/movies",
                Map.of("movieId", sampleMovie));

        assertEquals(400, res.getStatusCode().value());
    }

    @Test
    void addMovie_malformedBody_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.postForEntity(
                url("/watchlists/" + sampleList + "/movies"),
                new HttpEntity<>("{ this is not json", headers),
                String.class);

        assertEquals(400, res.getStatusCode().value());
    }

    @Test
    void listMovies_returns200() {
        post("/watchlists/" + sampleList + "/movies", Map.of("movieId", sampleMovie));
        ResponseEntity<String> res = rest.getForEntity(url("/watchlists/" + sampleList + "/movies"),
                String.class);

        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().contains(sampleMovie));
    }

    @Test
    void unsupportedMethod_returns405() {
        ResponseEntity<String> res = rest.exchange(
                url("/watchlists/" + sampleList + "/movies"),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of()),
                String.class);

        assertEquals(405, res.getStatusCode().value());
    }

    @Test
    void movieSearch_byActorDirectorAndReleaseYear_returnsMatches() {
        ResponseEntity<String> res = rest.getForEntity(
                url("/movies?actor=Anne&director=Nolan&releaseYear=2014&genre=sci-fi"),
                String.class);

        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().contains("Interstellar"));
        assertTrue(res.getBody().contains("Anne Hathaway"));
        assertTrue(res.getBody().contains("sci-fi"));
    }

    @Test
    void movieSearch_categoryAlias_returnsMatches() {
        ResponseEntity<String> res = rest.getForEntity(
                url("/movies?category=thriller"),
                String.class);

        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().contains("Inception"));
        assertTrue(res.getBody().contains("thriller"));
    }

    private ResponseEntity<String> post(String path, Map<String, String> body) {
        return rest.postForEntity(url(path), body, String.class);
    }

    private ResponseEntity<String> delete(String path) {
        return rest.exchange(url(path), HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
