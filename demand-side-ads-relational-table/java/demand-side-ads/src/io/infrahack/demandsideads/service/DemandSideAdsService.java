package io.infrahack.demandsideads.service;

import io.infrahack.demandsideads.enums.EventType;
import io.infrahack.demandsideads.exception.ValidationException;
import io.infrahack.demandsideads.model.*;
import io.infrahack.demandsideads.repository.AdsRepository;
import io.infrahack.demandsideads.repository.ReportingRepository;

import java.time.Instant;
import java.util.UUID;

public class DemandSideAdsService {
    private final AdsRepository adsRepository;
    private final ReportingRepository reportingRepository;

    public DemandSideAdsService(
            AdsRepository adsRepository,
            ReportingRepository reportingRepository
    ) {
        this.adsRepository = adsRepository;
        this.reportingRepository = reportingRepository;
    }

    public void createAdvertiser(Advertiser advertiser, String actor) {
        adsRepository.saveAdvertiser(advertiser);
        audit("ADVERTISER", advertiser.advertiserId(), "CREATED", actor);
    }

    public void createCampaign(Campaign campaign, String actor) {
        adsRepository.findAdvertiser(campaign.advertiserId())
                .orElseThrow(() -> new ValidationException("Advertiser not found"));

        adsRepository.saveCampaign(campaign);
        audit("CAMPAIGN", campaign.campaignId(), "CREATED", actor);
    }

    public void createAdGroup(AdGroup adGroup, String actor) {
        adsRepository.findCampaign(adGroup.campaignId())
                .orElseThrow(() -> new ValidationException("Campaign not found"));

        adsRepository.saveAdGroup(adGroup);
        audit("AD_GROUP", adGroup.adGroupId(), "CREATED", actor);
    }

    public void addTargetingCriterion(String adGroupId, TargetingCriterion criterion, String actor) {
        AdGroup adGroup = adsRepository.findAdGroup(adGroupId)
                .orElseThrow(() -> new ValidationException("Ad group not found"));

        adGroup.addTargetingCriterion(criterion);
        adsRepository.saveAdGroup(adGroup);
        audit("AD_GROUP", adGroupId, "TARGETING_UPDATED", actor);
    }

    public void createCreative(Creative creative, String actor) {
        adsRepository.findAdvertiser(creative.advertiserId())
                .orElseThrow(() -> new ValidationException("Advertiser not found"));

        adsRepository.saveCreative(creative);
        audit("CREATIVE", creative.creativeId(), "CREATED", actor);
    }

    public void createAd(Ad ad, String actor) {
        AdGroup adGroup = adsRepository.findAdGroup(ad.adGroupId())
                .orElseThrow(() -> new ValidationException("Ad group not found"));

        Campaign campaign = adsRepository.findCampaign(adGroup.campaignId())
                .orElseThrow(() -> new ValidationException("Campaign not found"));

        Creative creative = adsRepository.findCreative(ad.creativeId())
                .orElseThrow(() -> new ValidationException("Creative not found"));

        if (!creative.advertiserId().equals(campaign.advertiserId())) {
            throw new ValidationException("Creative must belong to the same advertiser as campaign");
        }

        adsRepository.saveAd(ad);
        audit("AD", ad.adId(), "CREATED", actor);
    }

    public AdEvent recordEvent(String eventId, EventType eventType, String adId) {
        Ad ad = adsRepository.findAd(adId)
                .orElseThrow(() -> new ValidationException("Ad not found"));

        AdGroup adGroup = adsRepository.findAdGroup(ad.adGroupId())
                .orElseThrow(() -> new ValidationException("Ad group not found"));

        Campaign campaign = adsRepository.findCampaign(adGroup.campaignId())
                .orElseThrow(() -> new ValidationException("Campaign not found"));

        AdEvent event = new AdEvent(
                eventId,
                eventType,
                Instant.now(),
                campaign.advertiserId(),
                campaign.campaignId(),
                adGroup.adGroupId(),
                ad.adId(),
                ad.creativeId(),
                campaign.version(),
                adGroup.version(),
                ad.version()
        );

        reportingRepository.save(event);
        return event;
    }

    public ReportingSummary summarizeCampaign(String campaignId) {
        var events = reportingRepository.findByCampaign(campaignId);

        long impressions = events.stream()
                .filter(e -> e.eventType() == EventType.IMPRESSION)
                .count();

        long clicks = events.stream()
                .filter(e -> e.eventType() == EventType.CLICK)
                .count();

        String advertiserId = adsRepository.findCampaign(campaignId)
                .map(Campaign::advertiserId)
                .orElse("unknown");

        return new ReportingSummary(advertiserId, campaignId, impressions, clicks);
    }

    private void audit(String entityType, String entityId, String action, String actor) {
        adsRepository.saveAuditEvent(new AuditEvent(
                UUID.randomUUID().toString(),
                entityType,
                entityId,
                action,
                actor,
                Instant.now()
        ));
    }
}