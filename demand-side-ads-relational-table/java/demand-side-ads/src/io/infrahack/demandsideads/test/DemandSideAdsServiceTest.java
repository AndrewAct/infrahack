package io.infrahack.demandsideads.test;

import io.infrahack.demandsideads.enums.*;
import io.infrahack.demandsideads.model.*;
import io.infrahack.demandsideads.repository.*;
import io.infrahack.demandsideads.service.DemandSideAdsService;

import java.math.BigDecimal;
import java.time.Instant;

public class DemandSideAdsServiceTest {
    public static void main(String[] args) {
        createsHierarchyAndReportsEvents();
        rejectsCreativeFromDifferentAdvertiser();
        System.out.println("All tests passed.");
    }

    static void createsHierarchyAndReportsEvents() {
        AdsRepository adsRepo = new InMemoryAdsRepository();
        ReportingRepository reportingRepo = new InMemoryReportingRepository();
        DemandSideAdsService service = new DemandSideAdsService(adsRepo, reportingRepo);

        Advertiser advertiser = new Advertiser("adv-1", "Apple");
        service.createAdvertiser(advertiser, "andrew");

        Campaign campaign = new Campaign(
                "camp-1",
                "adv-1",
                "iPhone Launch",
                new Budget(new BigDecimal("1000000"), "USD"),
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                PacingStrategy.EVEN
        );
        service.createCampaign(campaign, "andrew");

        AdGroup adGroup = new AdGroup(
                "ag-1",
                "camp-1",
                "US iOS Users",
                new Budget(new BigDecimal("50000"), "USD"),
                new BigDecimal("8.50"),
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                PacingStrategy.EVEN
        );
        service.createAdGroup(adGroup, "andrew");

        service.addTargetingCriterion("ag-1", new TargetingCriterion(
                "tc-1",
                TargetingDimension.GEO,
                TargetingOperator.INCLUDE,
                "COUNTRY",
                "US"
        ), "andrew");

        service.addTargetingCriterion("ag-1", new TargetingCriterion(
                "tc-2",
                TargetingDimension.DEVICE,
                TargetingOperator.INCLUDE,
                "OS",
                "IOS"
        ), "andrew");

        Creative creative = new Creative(
                "cr-1",
                "adv-1",
                CreativeType.VIDEO,
                "s3://ads/apple/iphone-launch.mp4"
        );
        service.createCreative(creative, "andrew");

        Ad ad = new Ad(
                "ad-1",
                "ag-1",
                "cr-1",
                "https://apple.com/iphone"
        );
        service.createAd(ad, "andrew");

        AdEvent impression = service.recordEvent("evt-1", EventType.IMPRESSION, "ad-1");
        service.recordEvent("evt-2", EventType.CLICK, "ad-1");

        assertEquals("adv-1", impression.advertiserId());
        assertEquals("camp-1", impression.campaignId());
        assertEquals("ag-1", impression.adGroupId());
        assertEquals("ad-1", impression.adId());
        assertEquals("cr-1", impression.creativeId());

        ReportingSummary summary = service.summarizeCampaign("camp-1");
        assertEquals(1L, summary.impressions());
        assertEquals(1L, summary.clicks());
    }

    static void rejectsCreativeFromDifferentAdvertiser() {
        AdsRepository adsRepo = new InMemoryAdsRepository();
        ReportingRepository reportingRepo = new InMemoryReportingRepository();
        DemandSideAdsService service = new DemandSideAdsService(adsRepo, reportingRepo);

        service.createAdvertiser(new Advertiser("adv-1", "Apple"), "andrew");
        service.createAdvertiser(new Advertiser("adv-2", "Tmall"), "andrew");

        service.createCampaign(new Campaign(
                "camp-1",
                "adv-1",
                "iPhone Launch",
                new Budget(new BigDecimal("1000000"), "USD"),
                Instant.now(),
                Instant.now().plusSeconds(86400),
                PacingStrategy.EVEN
        ), "andrew");

        service.createAdGroup(new AdGroup(
                "ag-1",
                "camp-1",
                "US Users",
                new Budget(new BigDecimal("50000"), "USD"),
                new BigDecimal("8.50"),
                Instant.now(),
                Instant.now().plusSeconds(86400),
                PacingStrategy.EVEN
        ), "andrew");

        service.createCreative(new Creative(
                "cr-foreign",
                "adv-2",
                CreativeType.IMAGE,
                "s3://ads/tmall/banner.png"
        ), "andrew");

        try {
            service.createAd(new Ad("ad-bad", "ag-1", "cr-foreign", "https://example.com"), "andrew");
            throw new AssertionError("Expected validation failure");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("Creative must belong"));
        }
    }

    static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }
}