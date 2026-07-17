package com.pixflow.infra.image.geometry;

public record RasterDimensions(long width, long height) {
    public RasterDimensions {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("raster dimensions must be positive");
        }
    }

    public long pixels() {
        return Math.multiplyExact(width, height);
    }

    public int intWidth() {
        return Math.toIntExact(width);
    }

    public int intHeight() {
        return Math.toIntExact(height);
    }
}
