package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.ContentType;

import java.time.Duration;

public class Episode extends ContentItem {
    private final int episodeNumber;
    private final Duration runtime;
    public Episode(String id, String canonicalTitle, int episodeNumber, Duration runtime) {
        super(id, canonicalTitle);
        this.episodeNumber = episodeNumber;
        this.runtime = runtime;
    }

    @Override
    public ContentType type() { return ContentType.EPISODE; }

    public int episodeNumber() { return episodeNumber; }
    public Duration runtime() { return runtime; }
}
