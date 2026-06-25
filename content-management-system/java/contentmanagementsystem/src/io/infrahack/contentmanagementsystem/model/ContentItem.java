package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.ContentType;
import io.infrahack.contentmanagementsystem.enums.LifecycleStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class ContentItem {
    protected final String id;
    protected final String canonicalTitle;
    protected LifecycleStatus lifecycleStatus = LifecycleStatus.DRAFT;
    protected Long version = 0L;

    protected final List<LocalizedMetadata> localizedMetadata = new ArrayList<>();
    protected final List<MediaAsset> assets = new ArrayList<>();
    protected final List<AvailabilityWindow> availabilityWindows = new ArrayList<>();

    protected ContentItem(String id, String canonicalTitle) {
        this.id = id;
        this.canonicalTitle = canonicalTitle;
    }

    public abstract ContentType type();

    public void addMetadata(LocalizedMetadata metadata) {
        localizedMetadata.add(metadata);
    }
    public void addAsset(MediaAsset asset) {
        assets.add(asset);
    }
    public void addAvailabilityWindow(AvailabilityWindow availabilityWindow) {
        availabilityWindows.add(availabilityWindow);
    }

    public boolean isPublishedAndAvailable(String region, Instant at) {
        return lifecycleStatus == LifecycleStatus.PUBLISHED &&
                availabilityWindows.stream().anyMatch(aw -> aw.covers(region, at));
    }

    Optional<LocalizedMetadata> metadataForLocale(String locale) {
        return localizedMetadata.stream().filter(lm -> lm.locale().equals(locale)).findFirst();
    }

    public boolean isValidTransition(LifecycleStatus from, LifecycleStatus to) {
        return switch (from) {
            case DRAFT -> to == LifecycleStatus.IN_REVIEW || to == LifecycleStatus.ARCHIVED;
            case IN_REVIEW -> to == LifecycleStatus.APPROVED || to == LifecycleStatus.ARCHIVED;
            case APPROVED -> to == LifecycleStatus.PUBLISHED || to == LifecycleStatus.ARCHIVED;
            case PUBLISHED -> to == LifecycleStatus.ARCHIVED;
            default -> false;
        };
    }

    public void incrementVersion() { version++; }

    public String id() { return id; }
    public String canonicalTitle() { return canonicalTitle; }
    public long version() { return version; }
    public List<MediaAsset> assets() { return assets; }
    public List<LocalizedMetadata> localizedMetadata() { return localizedMetadata; }
    public LifecycleStatus lifecycleStatus() { return lifecycleStatus; }

    public void transitionTo(LifecycleStatus to) {
        if (isValidTransition(lifecycleStatus, to)) {
            lifecycleStatus = to;
        } else {
            throw new IllegalStateException("Invalid transition from " + lifecycleStatus + " to " + to);
        }
    }
}
