package io.infrahack.moviewatchlistspring.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import io.infrahack.moviewatchlistspring.bootstrap.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full HTTP contract test through the real Spring MVC stack (controllers + @RestControllerAdvice +
 * Jackson). Each method reboots the context ({@link DirtiesContext}) so the in-memory store is fresh
 * and the seeded sample list starts with no movies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WatchListControllerTest {

    @Autowired
    private MockMvc mvc;

    private static final String LIST = SampleData.SAMPLE_WATCHLIST.toString();
    private static final String MOVIE = SampleData.MOVIES.get(0).id().toString(); // The Matrix

    private static String body(String movieId) {
        return "{\"movieId\":\"" + movieId + "\"}";
    }

    @Test
    void addMovie_returns201WithLocation() throws Exception {
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    void addMovie_duplicate_returns409() throws Exception {
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isCreated());
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isConflict());
    }

    @Test
    void removeMovie_present_returns204() throws Exception {
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isCreated());
        mvc.perform(delete("/watchlists/{l}/movies/{m}", LIST, MOVIE))
                .andExpect(status().isNoContent());
    }

    @Test
    void addMovie_missingList_returns404() throws Exception {
        String missing = UUID.randomUUID().toString();
        mvc.perform(post("/watchlists/{l}/movies", missing).contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeMovie_missingList_returns404() throws Exception {
        String missing = UUID.randomUUID().toString();
        mvc.perform(delete("/watchlists/{l}/movies/{m}", missing, MOVIE))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeMovie_notAMember_returns404() throws Exception {
        mvc.perform(delete("/watchlists/{l}/movies/{m}", LIST, MOVIE))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMovie_unknownMovie_returns404() throws Exception {
        String ghost = UUID.randomUUID().toString();
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content(body(ghost)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMovie_badUuidInPath_returns400() throws Exception {
        mvc.perform(post("/watchlists/{l}/movies", "not-a-uuid").contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addMovie_malformedBody_returns400() throws Exception {
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listMovies_returns200() throws Exception {
        mvc.perform(post("/watchlists/{l}/movies", LIST).contentType(MediaType.APPLICATION_JSON).content(body(MOVIE)))
                .andExpect(status().isCreated());
        mvc.perform(get("/watchlists/{l}/movies", LIST))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(MOVIE)));
    }
}
