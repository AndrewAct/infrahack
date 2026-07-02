package io.infrahack.moviewatchlistspring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.infrahack.moviewatchlistspring.exception.DuplicateMovieException;
import io.infrahack.moviewatchlistspring.exception.MovieNotFoundException;
import io.infrahack.moviewatchlistspring.exception.MovieNotInListException;
import io.infrahack.moviewatchlistspring.exception.WatchListNotFoundException;
import io.infrahack.moviewatchlistspring.model.Movie;
import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchList;
import io.infrahack.moviewatchlistspring.model.WatchListId;
import io.infrahack.moviewatchlistspring.repository.InMemoryMovieRepository;
import io.infrahack.moviewatchlistspring.repository.InMemoryWatchListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit test of the service (no Spring context) — the six scenarios plus the edge cases. */
class WatchListServiceTest {

    private InMemoryWatchListRepository watchLists;
    private InMemoryMovieRepository movies;
    private WatchListService service;
    private WatchListId listId;

    @BeforeEach
    void setUp() {
        watchLists = new InMemoryWatchListRepository();
        movies = new InMemoryMovieRepository();
        service = new WatchListService(watchLists, movies);
        listId = new WatchListId(UUID.randomUUID());
        watchLists.save(new WatchList(listId, UUID.randomUUID(), "My List"));
    }

    private MovieId seedMovie(String title) {
        MovieId id = new MovieId(UUID.randomUUID());
        movies.save(new Movie(id, title, 2000));
        return id;
    }

    @Test
    void addMovie_toExistingList_persistsMovie() {
        MovieId movie = seedMovie("Inception");
        service.addMovie(listId, movie);
        assertEquals(Set.of(movie), service.listMovies(listId));
    }

    @Test
    void addMovie_alreadyPresent_conflictAndNoDuplicate() {
        MovieId movie = seedMovie("Inception");
        service.addMovie(listId, movie);
        assertThrows(DuplicateMovieException.class, () -> service.addMovie(listId, movie));
        assertEquals(Set.of(movie), service.listMovies(listId));
    }

    @Test
    void removeMovie_present_removesIt() {
        MovieId movie = seedMovie("Inception");
        service.addMovie(listId, movie);
        service.removeMovie(listId, movie);
        assertTrue(service.listMovies(listId).isEmpty());
    }

    @Test
    void addMany_thenRemoveOneByOne_stateCorrectAtEachStep() {
        List<MovieId> added = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MovieId movie = seedMovie("Movie " + i);
            service.addMovie(listId, movie);
            added.add(movie);
            assertEquals(i + 1, service.listMovies(listId).size());
        }
        for (int i = 0; i < added.size(); i++) {
            service.removeMovie(listId, added.get(i));
            Set<MovieId> now = service.listMovies(listId);
            assertEquals(added.size() - (i + 1), now.size());
            assertTrue(now.containsAll(added.subList(i + 1, added.size())));
        }
    }

    @Test
    void addMovie_missingWatchList_notFound() {
        MovieId movie = seedMovie("Inception");
        WatchListId missing = new WatchListId(UUID.randomUUID());
        assertThrows(WatchListNotFoundException.class, () -> service.addMovie(missing, movie));
    }

    @Test
    void removeMovie_missingWatchList_notFound() {
        MovieId movie = seedMovie("Inception");
        WatchListId missing = new WatchListId(UUID.randomUUID());
        assertThrows(WatchListNotFoundException.class, () -> service.removeMovie(missing, movie));
    }

    @Test
    void addMovie_unknownMovie_notFound() {
        MovieId ghost = new MovieId(UUID.randomUUID());
        assertThrows(MovieNotFoundException.class, () -> service.addMovie(listId, ghost));
    }

    @Test
    void removeMovie_notAMember_notFoundNoFalseSuccess() {
        MovieId movie = seedMovie("Never added");
        assertThrows(MovieNotInListException.class, () -> service.removeMovie(listId, movie));
    }

    @Test
    void addMovie_bothMissing_reportsWatchListFirst() {
        WatchListId missingList = new WatchListId(UUID.randomUUID());
        MovieId missingMovie = new MovieId(UUID.randomUUID());
        assertThrows(WatchListNotFoundException.class, () -> service.addMovie(missingList, missingMovie));
    }
}
