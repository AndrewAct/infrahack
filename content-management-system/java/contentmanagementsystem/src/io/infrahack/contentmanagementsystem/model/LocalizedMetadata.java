package io.infrahack.contentmanagementsystem.model;

import java.util.Locale;

public record LocalizedMetadata(
        Locale locale,
        String title,
        String description
) {}
