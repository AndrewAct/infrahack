package io.infrahack.moviewatchlistspring.bootstrap;

import java.util.List;
import java.util.UUID;

import io.infrahack.moviewatchlistspring.model.Movie;
import io.infrahack.moviewatchlistspring.model.MovieId;
import io.infrahack.moviewatchlistspring.model.WatchList;
import io.infrahack.moviewatchlistspring.model.WatchListId;
import io.infrahack.moviewatchlistspring.repository.MovieRepository;
import io.infrahack.moviewatchlistspring.repository.WatchListRepository;

/** Fixed seed data. Same UUIDs as the raw module and {@code db/seed.sql}, so the same curls work. */
public final class SampleData {

    private SampleData() {}

    public static final WatchListId SAMPLE_WATCHLIST =
            new WatchListId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    public static final UUID SAMPLE_OWNER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    public static final List<Movie> MOVIES = List.of(
            movie("00000000-0000-0000-0000-000000000001", "The Matrix", 1999),
            movie("00000000-0000-0000-0000-000000000002", "Inception", 2010),
            movie("00000000-0000-0000-0000-000000000003", "Interstellar", 2014),
            movie("00000000-0000-0000-0000-000000000004", "The Dark Knight", 2008),
            movie("00000000-0000-0000-0000-000000000005", "Parasite", 2019),
            movie("00000000-0000-0000-0000-000000000006", "Spirited Away", 2001),
            movie("00000000-0000-0000-0000-000000000007", "Whiplash", 2014),
            movie("00000000-0000-0000-0000-000000000008", "Dune", 2021));

    public static void seed(WatchListRepository watchLists, MovieRepository movies) {
        watchLists.save(new WatchList(SAMPLE_WATCHLIST, SAMPLE_OWNER, "My Watch List"));
        MOVIES.forEach(movies::save);
    }

    private static Movie movie(String id, String title, int year) {
        return new Movie(new MovieId(UUID.fromString(id)), title, year);
    }
}
