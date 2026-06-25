package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.ContentType;

import java.time.Duration;

public class Movie extends ContentItem {
    private final Duration runtime;
    public Movie(String id, String canonicalTitle, Duration runtime) {
        super(id, canonicalTitle);
        this.runtime = runtime;
    }

    @Override
    public ContentType type() { return ContentType.MOVIE; }

    Duration runtime() { return runtime; }
}
