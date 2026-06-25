package io.infrahack.contentmanagementsystem.factory;

import io.infrahack.contentmanagementsystem.model.Episode;
import io.infrahack.contentmanagementsystem.model.Movie;
import io.infrahack.contentmanagementsystem.model.Season;
import io.infrahack.contentmanagementsystem.model.Series;

import java.time.Duration;
import java.util.UUID;

public class ContentFactory {
    public Movie createMovie(String title, Duration runtime) {
        return new Movie(UUID.randomUUID().toString(), title, runtime);
    }

    Series createSeries(String title) {
        return new Series(UUID.randomUUID().toString(), title);
    }

    Season createSeason(String title, int seasonNumber) {
        return new Season(UUID.randomUUID().toString(), title, seasonNumber);
    }

    Episode createEpisode(String title, int episodeNumber, Duration runtime) {
        return new Episode(UUID.randomUUID().toString(), title, episodeNumber, runtime);
    }
}
