package com.pixflow.infra.image;

import java.awt.image.BufferedImage;
import java.util.Objects;

public final class RasterImage {
    private final BufferedImage buffer;
    private final ImageFormat sourceFormat;

    private RasterImage(BufferedImage buffer, ImageFormat sourceFormat) {
        this.buffer = Objects.requireNonNull(buffer, "buffer must not be null");
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
    }

    public static RasterImage of(BufferedImage buffer, ImageFormat sourceFormat) {
        return new RasterImage(buffer, sourceFormat);
    }

    public BufferedImage buffer() {
        return buffer;
    }

    public int width() {
        return buffer.getWidth();
    }

    public int height() {
        return buffer.getHeight();
    }

    public boolean hasAlpha() {
        return buffer.getColorModel().hasAlpha();
    }

    public ImageFormat sourceFormat() {
        return sourceFormat;
    }
}
