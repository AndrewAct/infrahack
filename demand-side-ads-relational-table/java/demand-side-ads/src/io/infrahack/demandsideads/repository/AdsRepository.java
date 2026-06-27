package io.infrahack.demandsideads.repository;

import io.infrahack.demandsideads.model.*;

import java.util.Optional;

public interface AdsRepository {
    void saveAdvertiser(Advertiser advertiser);
    void saveCampaign(Campaign campaign);
    void saveAdGroup(AdGroup adGroup);
    void saveCreative(Creative creative);
    void saveAd(Ad ad);
    void saveAuditEvent(AuditEvent auditEvent);

    Optional<Advertiser> findAdvertiser(String advertiserId);
    Optional<Campaign> findCampaign(String campaignId);
    Optional<AdGroup> findAdGroup(String adGroupId);
    Optional<Creative> findCreative(String creativeId);
    Optional<Ad> findAd(String adId);
}
