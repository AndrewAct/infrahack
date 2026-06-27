package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.EventType;

import java.time.Instant;

public record AdEvent(String eventId,
                      EventType eventType,
                      Instant eventTime,
                      String advertiserId,
                      String campaignId,
                      String adGroupId,
                      String adId,
                      String creativeId,
                      long campaignVersion,
                      long adGroupVersion,
                      long adVersion) {
}
