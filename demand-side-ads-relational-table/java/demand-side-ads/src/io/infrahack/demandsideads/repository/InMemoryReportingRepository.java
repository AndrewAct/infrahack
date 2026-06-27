package io.infrahack.demandsideads.repository;

import io.infrahack.demandsideads.model.AdEvent;

import java.util.ArrayList;
import java.util.List;

public class InMemoryReportingRepository implements ReportingRepository{
    private final List<AdEvent> events = new ArrayList<>();
    @Override
    public void save(AdEvent adEvent) {
        events.add(adEvent);
    }

    @Override
    public List<AdEvent> findByCampaign(String campaignId) {
        return events.stream().filter(e -> e.campaignId().equals(campaignId)).toList();
    }
}
