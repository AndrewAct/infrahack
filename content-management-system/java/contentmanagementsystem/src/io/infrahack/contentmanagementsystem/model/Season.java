package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.ContentType;

import java.util.ArrayList;
import java.util.List;

public class Season extends ContentItem {
    private final int seasonNumber;
    private final List<Episode> episodes = new ArrayList<>();
    public Season(String id, String canonicalTitle, int seasonNumber) {
        super(id, canonicalTitle);
        this.seasonNumber = seasonNumber;
    }

    @Override
    public ContentType type() { return ContentType.SEASON; }

    public int seasonNumber() { return seasonNumber; }
    public void addEpisode(Episode episode) { episodes.add(episode); }
    public List<Episode> episodes() { return episodes; }
}
