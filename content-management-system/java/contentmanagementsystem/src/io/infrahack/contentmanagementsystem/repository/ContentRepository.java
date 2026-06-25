package io.infrahack.contentmanagementsystem.repository;

import io.infrahack.contentmanagementsystem.model.ContentItem;

import java.util.Optional;

public interface ContentRepository {
    Optional<ContentItem> findById(String id);
    void save(ContentItem contentItem, long version);
}
