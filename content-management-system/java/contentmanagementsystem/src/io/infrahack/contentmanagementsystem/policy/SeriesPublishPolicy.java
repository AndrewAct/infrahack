package io.infrahack.contentmanagementsystem.policy;

import io.infrahack.contentmanagementsystem.exception.ValidationException;
import io.infrahack.contentmanagementsystem.model.ContentItem;
import io.infrahack.contentmanagementsystem.model.Series;

public class SeriesPublishPolicy implements PublishPolicy{
    @Override
    public void validate(ContentItem contentItem) {
        Series series = (Series) contentItem;
        if (series.seasons().isEmpty()) {
            throw new ValidationException("Series must have at least one season");
        }
        if (series.seasons().stream().anyMatch(s -> s.episodes().isEmpty())) {
            throw new ValidationException("Series must have at least one episode in each season");
        }
    }
}
