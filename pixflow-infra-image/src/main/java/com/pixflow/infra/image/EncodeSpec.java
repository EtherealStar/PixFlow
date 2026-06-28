package com.pixflow.infra.image;

import java.awt.Color;

public record EncodeSpec(ImageFormat targetFormat, Integer quality, Long targetBytes, Color flattenBackground) {
    private static final long MAX_TARGET_BYTES = Integer.MAX_VALUE;

    public EncodeSpec {
        if (targetFormat == null) {
            throw new IllegalArgumentException("targetFormat must not be null");
        }
        if (quality != null && (quality < 1 || quality > 100)) {
            throw new IllegalArgumentException("quality must be between 1 and 100");
        }
        if (quality != null && targetBytes != null) {
            throw new IllegalArgumentException("quality and targetBytes are mutually exclusive");
        }
        if (targetBytes != null && targetBytes <= 0) {
            throw new IllegalArgumentException("targetBytes must be positive");
        }
        if (targetBytes != null && targetBytes > MAX_TARGET_BYTES) {
            throw new IllegalArgumentException("targetBytes must not exceed " + MAX_TARGET_BYTES);
        }
    }

    public static EncodeSpec of(ImageFormat targetFormat) {
        return new EncodeSpec(targetFormat, null, null, null);
    }
}
