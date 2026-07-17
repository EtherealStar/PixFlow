package com.pixflow.infra.image.geometry;

import com.pixflow.infra.image.op.ResizeSpec;
import java.util.Objects;

public final class ResizeGeometry {
    private ResizeGeometry() {
    }

    public static RasterDimensions resolve(RasterDimensions source, ResizeSpec spec) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        long boundWidth = spec.width() != null
                ? spec.width()
                : Math.max(1L, Math.round(source.width() * (spec.height() / (double) source.height())));
        long boundHeight = spec.height() != null
                ? spec.height()
                : Math.max(1L, Math.round(source.height() * (spec.width() / (double) source.width())));

        if (spec.mode() == ResizeSpec.Mode.FIT) {
            double scale = Math.min(boundWidth / (double) source.width(), boundHeight / (double) source.height());
            if (!spec.upscale()) {
                scale = Math.min(1.0d, scale);
            }
            return new RasterDimensions(
                    Math.max(1L, Math.round(source.width() * scale)),
                    Math.max(1L, Math.round(source.height() * scale)));
        }

        if (!spec.upscale()) {
            boundWidth = Math.min(boundWidth, source.width());
            boundHeight = Math.min(boundHeight, source.height());
        }
        return new RasterDimensions(Math.max(1L, boundWidth), Math.max(1L, boundHeight));
    }

    public static RasterDimensions fillIntermediate(RasterDimensions source, RasterDimensions target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        double scale = Math.max(
                target.width() / (double) source.width(),
                target.height() / (double) source.height());
        return new RasterDimensions(
                Math.max(1L, Math.round(source.width() * scale)),
                Math.max(1L, Math.round(source.height() * scale)));
    }
}
