package com.pixflow.infra.image;

public record ImageProbe(ImageFormat format, int width, int height, boolean hasAlpha) {
    public ImageProbe {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
    }
}
