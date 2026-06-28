package com.pixflow.infra.image;

import java.awt.image.BufferedImage;
import java.util.Objects;

public final class RasterImage {
    private final BufferedImage buffer;
    private final ImageFormat sourceFormat;

    private RasterImage(BufferedImage buffer, ImageFormat sourceFormat) {
        this.buffer = copyOf(Objects.requireNonNull(buffer, "buffer must not be null"));
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
    }

    public static RasterImage of(BufferedImage buffer, ImageFormat sourceFormat) {
        return new RasterImage(buffer, sourceFormat);
    }

    public BufferedImage buffer() {
        return copyOf(buffer);
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

    private static BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                source.getType() == BufferedImage.TYPE_CUSTOM
                        ? (source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB)
                        : source.getType());
        java.awt.Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
