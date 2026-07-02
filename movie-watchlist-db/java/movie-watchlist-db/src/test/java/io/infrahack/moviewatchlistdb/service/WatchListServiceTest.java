package io.infrahack.moviewatchlistdb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import io.infrahack.moviewatchlistdb.exception.DuplicateMovieException;
import io.infrahack.moviewatchlistdb.exception.MovieNotFoundException;
import io.infrahack.moviewatchlistdb.exception.MovieNotInListException;
import io.infrahack.moviewatchlistdb.exception.WatchListNotFoundException;
import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchList;
import io.infrahack.moviewatchlistdb.model.WatchListId;
import io.infrahack.moviewatchlistdb.repository.InMemoryMovieRepository;
import io.infrahack.moviewatchlistdb.repository.InMemoryWatchListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the six required scenarios plus the validation-ordering and persistence edge cases.
 * Runs against the in-memory repos with a same-thread executor so every future resolves
 * synchronously and assertions are deterministic.
 */
class WatchListServiceTest {

    // Same-thread executor: supplyAsync runs inline, so futures are already complete on return.
    private final java.util.concurrent.Executor inline = Runnable::run;

    private InMemoryWatchListRepository watchLists;
    private InMemoryMovieRepository movies;
    private WatchListService service;

    private WatchListId listId;

    @BeforeEach
    void setUp() {
        watchLists = new InMemoryWatchListRepository(inline);
        movies = new InMemoryMovieRepository(inline);
        service = new WatchListService(watchLists, movies);

        listId = new WatchListId(UUID.randomUUID());
        watchLists.save(new WatchList(listId, UUID.randomUUID(), "My List")).join();
    }

    /** Register a fresh movie in the catalog and return its id. */
    private MovieId seedMovie(String title) {
        MovieId id = new MovieId(UUID.randomUUID());
        movies.save(new Movie(id, title, 2000)).join();
        return id;
    }

    // --- Scenario 1: add a movie to an existing watch list ------------------------------------
    @Test
    void addMovie_toExistingList_persistsMovie() {
        MovieId movie = seedMovie("Inception");

        service.addMovie(listId, movie).join();

        assertEquals(Set.of(movie), movieEntries());
    }

    // --- Scenario 2: add a movie that is already present --------------------------------------
    @Test
    void addMovie_alreadyPresent_conflictAndNoDuplicate() {
        MovieId movie = seedMovie("Inception");
        service.addMovie(listId, movie).join();

        Throwable cause = causeOf(service.addMovie(listId, movie));

        assertInstanceOf(DuplicateMovieException.class, cause);
        assertEquals(Set.of(movie), movieEntries()); // still exactly one copy
    }

    // --- Scenario 3: remove a movie from an existing watch list -------------------------------
    @Test
    void removeMovie_present_removesIt() {
        MovieId movie = seedMovie("Inception");
        service.addMovie(listId, movie).join();

        service.removeMovie(listId, movie).join();

        assertTrue(movieEntries().isEmpty());
    }

    // --- Scenario 4: add many, then remove one by one -----------------------------------------
    @Test
    void addMany_thenRemoveOneByOne_stateCorrectAtEachStep() {
        List<MovieId> added = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MovieId movie = seedMovie("Movie " + i);
            service.addMovie(listId, movie).join();
            added.add(movie);
            assertEquals(i + 1, movieEntries().size()); // grows by exactly one each step
        }

        for (int i = 0; i < added.size(); i++) {
            service.removeMovie(listId, added.get(i)).join();
            int remaining = added.size() - (i + 1);
            Set<MovieId> now = movieEntries();
            assertEquals(remaining, now.size());              // shrinks by exactly one
            assertTrue(now.containsAll(added.subList(i + 1, added.size()))); // others untouched
        }
    }

    // --- Scenario 5: add to a watch list that does not exist ----------------------------------
    @Test
    void addMovie_missingWatchList_notFound() {
        MovieId movie = seedMovie("Inception");
        WatchListId missing = new WatchListId(UUID.randomUUID());

        assertInstanceOf(WatchListNotFoundException.class, causeOf(service.addMovie(missing, movie)));
    }

    // --- Scenario 6: remove from a watch list that does not exist -----------------------------
    @Test
    void removeMovie_missingWatchList_notFound() {
        WatchListId missing = new WatchListId(UUID.randomUUID());
        MovieId movie = seedMovie("Inception");

        assertInstanceOf(WatchListNotFoundException.class, causeOf(service.removeMovie(missing, movie)));
    }

    // --- Extra: unknown movie (not in catalog) ------------------------------------------------
    @Test
    void addMovie_unknownMovie_notFound() {
        MovieId ghost = new MovieId(UUID.randomUUID()); // never saved to the catalog

        assertInstanceOf(MovieNotFoundException.class, causeOf(service.addMovie(listId, ghost)));
    }

    // --- Extra: removing a movie that is not in the (existing) list ---------------------------
    @Test
    void removeMovie_notInList_notFoundNoFalseSuccess() {
        MovieId movie = seedMovie("Never added");

        assertInstanceOf(MovieNotInListException.class, causeOf(service.removeMovie(listId, movie)));
    }

    // --- Extra: validation ORDER — list checked before movie ----------------------------------
    @Test
    void addMovie_bothMissing_reportsWatchListFirst() {
        WatchListId missingList = new WatchListId(UUID.randomUUID());
        MovieId missingMovie = new MovieId(UUID.randomUUID());

        // List is validated first, so we get the list 404 even though the movie is also unknown.
        assertInstanceOf(WatchListNotFoundException.class,
                causeOf(service.addMovie(missingList, missingMovie)));
    }

    // --- Extra: the save is awaited (visible immediately after join) --------------------------
    @Test
    void addMovie_saveIsDurableBeforeCompletion() {
        MovieId movie = seedMovie("Interstellar");

        service.addMovie(listId, movie).join(); // if we hadn't awaited the save, this could race
        assertTrue(movieEntries().contains(movie));  // ...but the movie is already visible
    }

    private Set<MovieId> movieEntries() {
        return service.listMovies(listId).join();
    }

    /** Await a future expected to fail, returning the unwrapped domain cause. */
    private static Throwable causeOf(CompletableFuture<?> future) {
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        return ex.getCause();
    }
}
