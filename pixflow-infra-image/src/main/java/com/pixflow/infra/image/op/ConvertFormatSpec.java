package com.pixflow.infra.image.op;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageFormat;
import java.awt.Color;

public record ConvertFormatSpec(ImageFormat targetFormat, Integer quality, Color flattenBackground) {
    public ConvertFormatSpec {
        if (targetFormat == null) {
            throw new IllegalArgumentException("targetFormat must not be null");
        }
        if (quality != null && (quality < 1 || quality > 100)) {
            throw new IllegalArgumentException("quality must be between 1 and 100");
        }
    }

    public EncodeSpec toEncodeSpec() {
        return new EncodeSpec(targetFormat, quality, null, flattenBackground);
    }
}
