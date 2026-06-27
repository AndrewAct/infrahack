package io.infrahack.demandsideads.repository;

import io.infrahack.demandsideads.model.AdEvent;

import java.util.List;

public interface ReportingRepository {
    void save(AdEvent adEvent);
    List<AdEvent> findByCampaign(String campaignId);
}
