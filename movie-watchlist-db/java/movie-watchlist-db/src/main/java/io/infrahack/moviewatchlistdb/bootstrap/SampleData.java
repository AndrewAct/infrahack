package io.infrahack.moviewatchlistdb.bootstrap;

import java.util.List;
import java.util.UUID;

import io.infrahack.moviewatchlistdb.model.Movie;
import io.infrahack.moviewatchlistdb.model.MovieId;
import io.infrahack.moviewatchlistdb.model.WatchList;
import io.infrahack.moviewatchlistdb.model.WatchListId;
import io.infrahack.moviewatchlistdb.repository.MovieRepository;
import io.infrahack.moviewatchlistdb.repository.WatchListRepository;

/**
 * Fixed seed data used to make the app runnable out of the box. The UUIDs here are the SAME literals
 * used in {@code db/seed.sql}, so the identical curl commands work whether you run in-memory or on
 * Postgres.
 */
public final class SampleData {

    private SampleData() {}

    public static final WatchListId SAMPLE_WATCHLIST =
            new WatchListId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    public static final UUID SAMPLE_OWNER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    public static final List<Movie> MOVIES = List.of(
            movie("00000000-0000-0000-0000-000000000001", "The Matrix", 1999,
                    "Lana Wachowski",
                    List.of("Keanu Reeves", "Carrie-Anne Moss", "Laurence Fishburne"),
                    List.of("sci-fi", "action")),
            movie("00000000-0000-0000-0000-000000000002", "Inception", 2010,
                    "Christopher Nolan",
                    List.of("Leonardo DiCaprio", "Joseph Gordon-Levitt", "Elliot Page"),
                    List.of("sci-fi", "thriller")),
            movie("00000000-0000-0000-0000-000000000003", "Interstellar", 2014,
                    "Christopher Nolan",
                    List.of("Matthew McConaughey", "Anne Hathaway", "Jessica Chastain"),
                    List.of("sci-fi", "drama")),
            movie("00000000-0000-0000-0000-000000000004", "The Dark Knight", 2008,
                    "Christopher Nolan",
                    List.of("Christian Bale", "Heath Ledger", "Aaron Eckhart"),
                    List.of("action", "crime", "drama")),
            movie("00000000-0000-0000-0000-000000000005", "Parasite", 2019,
                    "Bong Joon Ho",
                    List.of("Song Kang-ho", "Lee Sun-kyun", "Cho Yeo-jeong"),
                    List.of("thriller", "drama", "comedy")),
            movie("00000000-0000-0000-0000-000000000006", "Spirited Away", 2001,
                    "Hayao Miyazaki",
                    List.of("Rumi Hiiragi", "Miyu Irino", "Mari Natsuki"),
                    List.of("animation", "fantasy", "adventure")),
            movie("00000000-0000-0000-0000-000000000007", "Whiplash", 2014,
                    "Damien Chazelle",
                    List.of("Miles Teller", "J.K. Simmons", "Melissa Benoist"),
                    List.of("drama", "music")),
            movie("00000000-0000-0000-0000-000000000008", "Dune", 2021,
                    "Denis Villeneuve",
                    List.of("Timothee Chalamet", "Rebecca Ferguson", "Oscar Isaac"),
                    List.of("sci-fi", "adventure")));

    /** Seed an empty (in-memory) store: one sample watch list plus the catalog. */
    public static void seed(WatchListRepository watchLists, MovieRepository movies) {
        watchLists.save(new WatchList(SAMPLE_WATCHLIST, SAMPLE_OWNER, "My Watch List")).join();
        MOVIES.forEach(m -> movies.save(m).join());
    }

    private static Movie movie(String id,
                               String title,
                               int year,
                               String director,
                               List<String> actors,
                               List<String> genres) {
        return new Movie(new MovieId(UUID.fromString(id)), title, year, director, actors, genres);
    }
}
