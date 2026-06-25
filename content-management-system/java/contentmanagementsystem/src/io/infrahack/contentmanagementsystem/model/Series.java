package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.ContentType;

import java.util.ArrayList;
import java.util.List;

public class Series extends ContentItem {
    private final List<Season> seasons = new ArrayList<>();
    public Series(String id, String canonicalTitle) {
        super(id, canonicalTitle);
    }
    @Override
    public ContentType type() { return ContentType.SERIES; }
    public void addSeason(Season season) { seasons.add(season); }
    public List<Season> seasons() { return seasons; }
}
