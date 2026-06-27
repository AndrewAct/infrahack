package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.PacingStrategy;
import io.infrahack.demandsideads.enums.Status;

import java.time.Instant;

/**
 * Campaign is the activity that an advertiser can perform. Like "Apple: Home back season" or NPI
 */
public class Campaign {
    private final String campaignId;
    private final String advertiserId;
    private final String name;
    private final Budget totalBudget;
    private final Instant startAt;
    private final Instant endAt;
    private final PacingStrategy pacingStrategy;
    private Status status;
    private long version;

    public Campaign(String campaignId, String advertiserId, String name, Budget totalBudget, Instant startAt, Instant endAt, PacingStrategy pacingStrategy) {
        this.campaignId = campaignId;
        this.advertiserId = advertiserId;
        this.name = name;
        this.totalBudget = totalBudget;
        this.startAt = startAt;
        this.endAt = endAt;
        this.pacingStrategy = pacingStrategy;
        this.status = Status.DRAFT; // default status is draft
        this.version = 0; // default version is 0
    }

    // Initialize a new campaign
    public void activate() {
        status = Status.ACTIVE;
        version++;
    }

    public void pause() {
        status = Status.PAUSED;
        version++;
    }

    public void archive() {
        status = Status.ARCHIVED;
        version++;
    }

    public String campaignId() {
        return campaignId;
    }

    public String advertiserId() {
        return advertiserId;
    }

    public String name() {
        return name;
    }

    public Budget totalBudget() {
        return totalBudget;
    }

    public Instant startAt() {
        return startAt;
    }

    public PacingStrategy pacingStrategy() {
        return pacingStrategy;
    }

    public Status status() {
        return status;
    }

    public long version() {
        return version;
    }
}
