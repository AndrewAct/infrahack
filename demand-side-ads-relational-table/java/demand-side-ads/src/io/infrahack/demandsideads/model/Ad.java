package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.Status;

/**
 * Ad is the entity (an advertisement) that will be released
 */
public class Ad {
    private final String adId;
    private final String adGroupId;
    private final String creativeId;
    // Impression is not explicitly configurable here, so skipped
    private final String clickUrl;
    private Status status;
    private long version;

    public Ad(String adId, String adGroupId, String creativeId, String clickUrl) {
        this.adId = adId;
        this.adGroupId = adGroupId;
        this.creativeId = creativeId;
        this.clickUrl = clickUrl;
        this.status = Status.DRAFT;
        this.version = 0;
    }

    public void activate() {
        status = Status.ACTIVE;
        version++;
    }

    public void archive() {
        status = Status.ARCHIVED;
    }

    public String adId() { return adId; }
    public String adGroupId() { return adGroupId; }
    public String creativeId() { return creativeId; }
    public String clickUrl() { return clickUrl; }
    public Status status() { return status; }
    public long version() { return version; }
}
