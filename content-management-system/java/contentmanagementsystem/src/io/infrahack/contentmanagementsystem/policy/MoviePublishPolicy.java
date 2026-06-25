package io.infrahack.contentmanagementsystem.policy;

import io.infrahack.contentmanagementsystem.enums.AssetStatus;
import io.infrahack.contentmanagementsystem.enums.AssetType;
import io.infrahack.contentmanagementsystem.exception.ValidationException;
import io.infrahack.contentmanagementsystem.model.ContentItem;

public class MoviePublishPolicy implements PublishPolicy{
    @Override
    public void validate(ContentItem contentItem) {
        boolean hasVideo = contentItem.assets().stream()
                .anyMatch(a -> a.type().equals(AssetType.VIDEO)
                        && a.status().equals(AssetStatus.VALIDATED));
        boolean hasMetadata = !contentItem.localizedMetadata().isEmpty();
        if (!hasVideo || !hasMetadata) {
            throw new ValidationException("Movie must have a video and metadata");
        }
    }
}
