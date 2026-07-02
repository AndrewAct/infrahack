package io.infrahack.moviewatchlistspring.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchListId;
import io.infrahack.moviewatchlistspring.service.WatchListService;

/**
 * REST controller for watch-list membership (the Spring equivalent of the raw module's HttpHandler).
 * <pre>
 *   POST   /watchlists/{id}/movies    {"movieId":"&lt;uuid&gt;"}  -> 201 (+ Location)
 *   DELETE /watchlists/{id}/movies/{movieId}                  -> 204
 *   GET    /watchlists/{id}/movies                            -> 200
 * </pre>
 * Thin controller: it only translates HTTP &lt;-&gt; domain and delegates to the service. Bad UUIDs and
 * malformed bodies surface as exceptions that {@link ApiExceptionHandler} maps to 400.
 */
@RestController
@RequestMapping("/watchlists/{watchListId}/movies")
public class WatchListController {

    private final WatchListService service;

    public WatchListController(WatchListService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AddMovieResponse> add(@PathVariable UUID watchListId,
                                                @RequestBody(required = false) AddMovieRequest body) {
        if (body == null || body.movieId() == null || body.movieId().isBlank()) {
            throw new IllegalArgumentException("Field 'movieId' is required");
        }
        WatchListId listId = new WatchListId(watchListId);
        MovieId movieId = MovieId.of(body.movieId()); // bad UUID -> IllegalArgumentException -> 400
        service.addMovie(listId, movieId);
        URI location = URI.create("/watchlists/" + listId + "/movies/" + movieId);
        return ResponseEntity.created(location).body(new AddMovieResponse(listId.toString(), movieId.toString()));
    }

    @DeleteMapping("/{movieId}")
    public ResponseEntity<Void> remove(@PathVariable UUID watchListId, @PathVariable UUID movieId) {
        service.removeMovie(new WatchListId(watchListId), new MovieId(movieId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public MoviesResponse list(@PathVariable UUID watchListId) {
        List<String> movies = service.listMovies(new WatchListId(watchListId)).stream()
                .map(MovieId::toString)
                .toList();
        return new MoviesResponse(watchListId.toString(), movies);
    }

    record AddMovieRequest(String movieId) {}
    record AddMovieResponse(String watchListId, String movieId) {}
    record MoviesResponse(String watchListId, List<String> movies) {}
}
