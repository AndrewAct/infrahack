package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.CreativeType;
import io.infrahack.demandsideads.enums.Status;

public class Creative {
    private final String creativeId;
    private final String advertiserId;
    private final CreativeType creativeType;
    private final String assetUrl;
    private Status status;

    public Creative(String creativeId, String advertiserId, CreativeType creativeType, String assetUrl) {
        this.creativeId = creativeId;
        this.advertiserId = advertiserId;
        this.creativeType = creativeType;
        this.assetUrl = assetUrl;
        this.status = Status.ACTIVE; // default status is active for creative
    }

    public String creativeId() { return creativeId; }
    public String advertiserId() { return advertiserId; }
    public CreativeType creativeType() { return creativeType; }
    public String assetUrl() { return assetUrl; }
    public Status status() { return status; }

}
