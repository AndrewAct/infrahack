package io.infrahack.contentmanagementsystem.model;

import io.infrahack.contentmanagementsystem.enums.AssetStatus;
import io.infrahack.contentmanagementsystem.enums.AssetType;

import java.util.Locale;

public record MediaAsset(
        String id,
        AssetType type,
        Locale locale,
        String storageUri,
        AssetStatus status
) {
}
