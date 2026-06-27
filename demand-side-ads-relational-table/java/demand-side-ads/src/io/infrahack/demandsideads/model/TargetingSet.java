package io.infrahack.demandsideads.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Targeting set is a set of targeting rules that will be applied to the ad
 * Like "Young adults" or "US"
 */
public class TargetingSet {
    private final String targetingSetId;
    // One targeting set can be shared by multiple ad groups or campaigns
    private final String ownerType;
    private final String ownerId;
    private final List<TargetingCriterion> targetingCriteria = new ArrayList<>();

    public TargetingSet(String targetingSetId, String ownerType, String ownerId) {
        this.targetingSetId = targetingSetId;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
    }

    public void addCriterion(TargetingCriterion criterion) {
        targetingCriteria.add(criterion);
    }

    public String targetingSetId() { return targetingSetId; }
    public String ownerType() { return ownerType; }
    public String ownerId() { return ownerId; }
    public List<TargetingCriterion> criteria() { return List.copyOf(targetingCriteria); }
}
