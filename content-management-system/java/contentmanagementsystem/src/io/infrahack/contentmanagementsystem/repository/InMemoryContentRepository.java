package io.infrahack.contentmanagementsystem.repository;

import io.infrahack.contentmanagementsystem.exception.StaleObjectException;
import io.infrahack.contentmanagementsystem.model.ContentItem;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryContentRepository implements ContentRepository {
    private final Map<String, ContentItem> contentItems = new HashMap<>();

    @Override
    public Optional<ContentItem> findById(String id) {
        return Optional.ofNullable(contentItems.get(id));
    }

    @Override
    public void save(ContentItem contentItem, long version) {
        ContentItem existingContentItem = contentItems.get(contentItem.id());
        if (existingContentItem != null && existingContentItem.version() > version) {
            throw  new StaleObjectException("Expected version " + version);
        }
        contentItem.incrementVersion();
        contentItems.put(contentItem.id(), contentItem);
    }
}
