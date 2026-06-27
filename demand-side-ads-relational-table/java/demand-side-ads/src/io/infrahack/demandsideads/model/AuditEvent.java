package io.infrahack.demandsideads.model;

import java.time.Instant;

public record AuditEvent(
        String auditEventId,
        String entityType, // It can be a campaign, ad group, ad, etc.
        String entityId, // The ID of the entity that was affected by the action.
        String action,
        String actor,
        Instant createdAt
) {
}
