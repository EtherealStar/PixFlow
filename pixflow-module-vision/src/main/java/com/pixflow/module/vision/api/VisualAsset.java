package com.pixflow.module.vision.api;

import com.pixflow.infra.image.ReopenableImageSource;
import java.util.Objects;

public record VisualAsset(
        long packageId,
        String skuId,
        long imageId,
        String contentHash,
        long sizeBytes,
        String contentType,
        ReopenableImageSource source) {

    public VisualAsset {
        if (packageId <= 0 || imageId <= 0 || sizeBytes < 0) {
            throw new IllegalArgumentException("invalid visual asset identity or size");
        }
        skuId = requireText(skuId, "skuId");
        contentHash = requireText(contentHash, "contentHash").toLowerCase(java.util.Locale.ROOT);
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a SHA-256 hex digest");
        }
        contentType = requireText(contentType, "contentType");
        source = Objects.requireNonNull(source, "source");
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
