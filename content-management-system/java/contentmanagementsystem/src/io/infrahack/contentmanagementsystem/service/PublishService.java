package io.infrahack.contentmanagementsystem.service;

import io.infrahack.contentmanagementsystem.enums.ContentType;
import io.infrahack.contentmanagementsystem.enums.LifecycleStatus;
import io.infrahack.contentmanagementsystem.exception.ValidationException;
import io.infrahack.contentmanagementsystem.model.ContentItem;
import io.infrahack.contentmanagementsystem.model.ContentSnapshot;
import io.infrahack.contentmanagementsystem.model.User;
import io.infrahack.contentmanagementsystem.policy.MoviePublishPolicy;
import io.infrahack.contentmanagementsystem.policy.PublishPolicy;
import io.infrahack.contentmanagementsystem.policy.SeriesPublishPolicy;
import io.infrahack.contentmanagementsystem.repository.ContentRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PublishService {
    private final ContentRepository contentRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MetricsCollector metricsCollector;
    private final Map<ContentType, PublishPolicy> policies;

    public PublishService(ContentRepository contentRepository, PermissionService permissionService, AuditService auditService, MetricsCollector metricsCollector) {
        this.contentRepository = contentRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.metricsCollector = metricsCollector;
        this.policies = Map.of(ContentType.MOVIE, new MoviePublishPolicy(),
                ContentType.SERIES, new SeriesPublishPolicy());
    }

    public ContentSnapshot publish(String contentId, User user) {
        permissionService.requiredPublish(user);
        ContentItem contentItem = contentRepository.findById(contentId)
                .orElseThrow(() -> new ValidationException("Content not found"));
        PublishPolicy policy = policies.get(contentItem.type());
        if (policy != null) {
            policy.validate(contentItem);
        }
        long version = contentItem.version();
        contentItem.transitionTo(LifecycleStatus.PUBLISHED);
        contentRepository.save(contentItem, version);
        auditService.record(user, "PUBLISHED", contentItem.id());
        metricsCollector.increment("content.published");
        return new ContentSnapshot(
                UUID.randomUUID().toString(),
                contentItem.id(),
                (int) contentItem.version(),
                contentItem.lifecycleStatus(),
                Instant.now()
        );
    }
}
