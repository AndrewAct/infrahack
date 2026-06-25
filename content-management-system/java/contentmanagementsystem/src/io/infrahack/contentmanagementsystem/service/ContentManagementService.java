package io.infrahack.contentmanagementsystem.service;

import io.infrahack.contentmanagementsystem.enums.LifecycleStatus;
import io.infrahack.contentmanagementsystem.exception.ValidationException;
import io.infrahack.contentmanagementsystem.model.ContentItem;
import io.infrahack.contentmanagementsystem.model.LocalizedMetadata;
import io.infrahack.contentmanagementsystem.model.User;
import io.infrahack.contentmanagementsystem.repository.ContentRepository;

public class ContentManagementService {
    private final ContentRepository contentRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MetricsCollector metricsCollector;

    public ContentManagementService(ContentRepository contentRepository, PermissionService permissionService, AuditService auditService, MetricsCollector metricsCollector) {
        this.contentRepository = contentRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.metricsCollector = metricsCollector;
    }

    public void create(ContentItem contentItem, User user) {
        permissionService.requiredEdit(user);
        contentRepository.save(contentItem, contentItem.version());
        auditService.record(user, "CREATED", contentItem.id());
        metricsCollector.increment("content.created");
    }

    void update(ContentItem contentItem, User user) {
        permissionService.requiredEdit(user);
        contentRepository.save(contentItem, contentItem.version());
        auditService.record(user, "UPDATED", contentItem.id());
        metricsCollector.increment("content.updated");
    }

    public void addMetadata(String contentId, LocalizedMetadata metadata, User user) {
        permissionService.requiredEdit(user);
        ContentItem contentItem = contentRepository.findById(contentId)
                .orElseThrow(() -> new ValidationException("Content not found"));
        long expectedVersion = contentItem.version();
        contentItem.addMetadata(metadata);
        contentRepository.save(contentItem, expectedVersion);
        auditService.record(user, "METADATA_ADDED", contentId);
        metricsCollector.increment("content.metadata.added");
    }

    void submitForReview(String contentId, User user) {
        permissionService.requiredEdit(user);
        ContentItem contentItem = contentRepository.findById(contentId)
                .orElseThrow(() -> new ValidationException("Content not found"));
        long expectedVersion = contentItem.version();
        contentItem.transitionTo(LifecycleStatus.IN_REVIEW);
        contentRepository.save(contentItem, expectedVersion);
        auditService.record(user, "SUBMITTED_FOR_REVIEW", contentId);
        metricsCollector.increment("content.submitted_for_review");
    }
}
