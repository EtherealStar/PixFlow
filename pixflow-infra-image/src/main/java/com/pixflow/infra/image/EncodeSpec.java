package com.pixflow.infra.image;

import java.awt.Color;

public record EncodeSpec(ImageFormat targetFormat, Integer quality, Long targetBytes, Color flattenBackground) {
    public EncodeSpec {
        if (targetFormat == null) {
            throw new IllegalArgumentException("targetFormat must not be null");
        }
        if (quality != null && (quality < 1 || quality > 100)) {
            throw new IllegalArgumentException("quality must be between 1 and 100");
        }
        if (targetBytes != null && targetBytes <= 0) {
            throw new IllegalArgumentException("targetBytes must be positive");
        }
    }

    public static EncodeSpec of(ImageFormat targetFormat) {
        return new EncodeSpec(targetFormat, null, null, null);
    }
}
