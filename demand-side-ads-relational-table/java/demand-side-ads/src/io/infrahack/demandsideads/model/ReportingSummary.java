package io.infrahack.demandsideads.model;

public record ReportingSummary(
        String advertiserId,
        String campaignId,
        long impressions,
        long clicks
) {
}
