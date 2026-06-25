package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.LifecycleStatus;

import java.time.Instant;

public record ContentSnapshot(
        String snapShotId,
        String contentId,
        int snapShotVersion,
        LifecycleStatus lifecycleStatus,
        Instant createdAt
) { }
