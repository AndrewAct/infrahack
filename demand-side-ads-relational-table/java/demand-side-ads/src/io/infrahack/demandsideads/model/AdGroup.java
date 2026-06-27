package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.PacingStrategy;
import io.infrahack.demandsideads.enums.Status;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * AdGroup is a collection of ads that released in a campaign. They share the same rules, which will be specified below
 */
public class AdGroup {
    private final String adGroupId;
    private final String campaignId;
    private final String name;
    private final Budget dailyBudget;
    private final BigDecimal bidAmount;
    private final Instant startAt;
    private final Instant endAt;
    private final PacingStrategy pacingStrategy;
    private final TargetingSet targetingSet;
    private Status status;
    private long version;

    public AdGroup(String adGroupId, String campaignId, String name, Budget dailyBudget, BigDecimal bidAmount, Instant startAt, Instant endAt, PacingStrategy pacingStrategy) {
        this.adGroupId = adGroupId;
        this.campaignId = campaignId;
        this.name = name;
        this.dailyBudget = dailyBudget;
        this.bidAmount = bidAmount;
        this.startAt = startAt;
        this.endAt = endAt;
        this.pacingStrategy = pacingStrategy;
        this.targetingSet = new TargetingSet("ts-" + adGroupId, "AD_GROUP", adGroupId);;
        this.status = Status.DRAFT;
        this.version = 0;
    }

    public void addTargetingCriterion(TargetingCriterion criterion) {
        targetingSet.addCriterion(criterion);
        version++;
    }

    public void activate() {
        status = Status.ACTIVE;
        version++;
    }

    public void archive() {
        status = Status.ARCHIVED;
    }

    public String adGroupId() { return adGroupId; }
    public String campaignId() { return campaignId; }
    public String name() { return name; }
    public Budget dailyBudget() { return dailyBudget; }
    public BigDecimal bidAmount() { return bidAmount; }
    public Instant startAt() { return startAt; }
    public Instant endAt() { return endAt; }
    public PacingStrategy pacingStrategy() { return pacingStrategy; }
    public TargetingSet targetingSet() { return targetingSet; }
    public Status status() { return status; }
    public long version() { return version; }
}
