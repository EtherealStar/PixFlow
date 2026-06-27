package com.pixflow.infra.image.op;

import com.pixflow.infra.image.RasterImage;

public record WatermarkSpec(RasterImage watermark, Position position, float opacity, double scale, int margin) {
    public enum Position {
        TOP_LEFT,
        TOP_CENTER,
        TOP_RIGHT,
        CENTER_LEFT,
        CENTER,
        CENTER_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_CENTER,
        BOTTOM_RIGHT
    }

    public WatermarkSpec {
        if (watermark == null) {
            throw new IllegalArgumentException("watermark must not be null");
        }
        position = position == null ? Position.BOTTOM_RIGHT : position;
        if (opacity < 0f || opacity > 1f) {
            throw new IllegalArgumentException("opacity must be between 0 and 1");
        }
        if (scale <= 0d) {
            throw new IllegalArgumentException("scale must be positive");
        }
        if (margin < 0) {
            throw new IllegalArgumentException("margin must not be negative");
        }
    }
}
