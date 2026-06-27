package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.Status;

import java.time.Instant;

public class Advertiser {
    private final String advertiserId;
    private final String name;
    private Status status;
    private long version;
    private final Instant createdAt;

    public Advertiser(String advertiserId, String name) {
        this.advertiserId = advertiserId;
        this.name = name;
        this.status = Status.ACTIVE; // default status is active for advertiser
        this.createdAt = Instant.now();
    }

    public void activate() {
        status = Status.ACTIVE;
        version++;
    }

    // In theory, if we pause an advertiser, its version should be incremented.
    // but it depends on the logic. In interview, we may not need it (?)
    public void pause() {
        status = Status.PAUSED;
        version++;
    }

    public void archive() {
        status = Status.ARCHIVED;
        version++;
    }

    public String advertiserId() { return advertiserId; }
    public String name() { return name; }
    public Status status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
