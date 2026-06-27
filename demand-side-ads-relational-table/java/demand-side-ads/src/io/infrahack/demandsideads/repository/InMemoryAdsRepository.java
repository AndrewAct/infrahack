package io.infrahack.demandsideads.repository;

import io.infrahack.demandsideads.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryAdsRepository implements AdsRepository {
    private final Map<String, Advertiser> advertisers = new HashMap<>();
    private final Map<String, Campaign> campaigns = new HashMap<>();
    private final Map<String, AdGroup> adGroups = new HashMap<>();
    private final Map<String, Creative> creatives = new HashMap<>();
    private final Map<String, Ad> ads = new HashMap<>();
    private final Map<String, AuditEvent> auditEvents = new HashMap<>();

    @Override
    public void saveAdvertiser(Advertiser advertiser) {
        advertisers.put(advertiser.advertiserId(), advertiser);
    }

    @Override
    public void saveCampaign(Campaign campaign) {
        campaigns.put(campaign.campaignId(), campaign);
    }

    @Override
    public void saveAdGroup(AdGroup adGroup) {
        adGroups.put(adGroup.adGroupId(), adGroup);
    }

    @Override
    public void saveCreative(Creative creative) {
        creatives.put(creative.creativeId(), creative);
    }

    @Override
    public void saveAd(Ad ad) {
        ads.put(ad.adId(), ad);
    }

    @Override
    public void saveAuditEvent(AuditEvent auditEvent) {
        auditEvents.put(auditEvent.auditEventId(), auditEvent);
    }

    @Override
    public Optional<Advertiser> findAdvertiser(String advertiserId) {
        return Optional.ofNullable(advertisers.get(advertiserId));
    }

    @Override
    public Optional<Campaign> findCampaign(String campaignId) {
        return Optional.ofNullable(campaigns.get(campaignId));
    }

    @Override
    public Optional<AdGroup> findAdGroup(String adGroupId) {
        return Optional.ofNullable(adGroups.get(adGroupId));
    }

    @Override
    public Optional<Creative> findCreative(String creativeId) {
        return Optional.ofNullable(creatives.get(creativeId));
    }

    @Override
    public Optional<Ad> findAd(String adId) {
        return Optional.ofNullable(ads.get(adId));
    }

}
