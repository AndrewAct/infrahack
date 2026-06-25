package io.infrahack.contentmanagementsystem.policy;

import io.infrahack.contentmanagementsystem.model.ContentItem;

public interface PublishPolicy {
    void validate(ContentItem contentItem);
}
