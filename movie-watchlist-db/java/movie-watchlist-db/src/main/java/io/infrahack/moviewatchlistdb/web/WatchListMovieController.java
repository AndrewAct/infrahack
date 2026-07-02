package io.infrahack.moviewatchlistdb.web;

import java.net.URI;
import java.util.List;

import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchListId;
import io.infrahack.moviewatchlistdb.service.WatchListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/watchlists/{watchListId}/movies")
public class WatchListMovieController {

    private final WatchListService service;

    public WatchListMovieController(WatchListService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AddMovieResponse> addMovie(@PathVariable String watchListId,
                                                     @RequestBody AddMovieRequest request) {
        WatchListId listId = WatchListId.of(watchListId);
        MovieId movieId = MovieId.of(requiredMovieId(request));

        service.addMovie(listId, movieId).join();

        URI location = URI.create("/watchlists/" + listId + "/movies/" + movieId);
        return ResponseEntity.created(location)
                .body(new AddMovieResponse(listId.toString(), movieId.toString()));
    }

    @DeleteMapping("/{movieId}")
    public ResponseEntity<Void> removeMovie(@PathVariable String watchListId,
                                            @PathVariable String movieId) {
        service.removeMovie(WatchListId.of(watchListId), MovieId.of(movieId)).join();
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public MoviesResponse listMovies(@PathVariable String watchListId) {
        WatchListId listId = WatchListId.of(watchListId);
        List<String> movies = service.listMovies(listId).join().stream()
                .map(MovieId::toString)
                .toList();
        return new MoviesResponse(listId.toString(), movies);
    }

    private static String requiredMovieId(AddMovieRequest request) {
        if (request == null || request.movieId() == null || request.movieId().isBlank()) {
            throw new IllegalArgumentException("Field 'movieId' is required");
        }
        return request.movieId();
    }

    public record AddMovieRequest(String movieId) {}
    public record AddMovieResponse(String watchListId, String movieId) {}
    public record MoviesResponse(String watchListId, List<String> movies) {}
}
