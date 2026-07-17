package com.pixflow.infra.image;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class RasterImage implements AutoCloseable {
    private final SharedRaster shared;

    private final ImageFormat sourceFormat;

    private boolean closed;

    private RasterImage(SharedRaster shared, ImageFormat sourceFormat) {
        this.shared = Objects.requireNonNull(shared, "shared must not be null");
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
    }

    public static RasterImage takeOwnership(BufferedImage buffer, ImageFormat sourceFormat) {
        return new RasterImage(
                new SharedRaster(Objects.requireNonNull(buffer, "buffer must not be null")),
                sourceFormat);
    }

    public synchronized RasterImage retain() {
        ensureOpen();
        shared.retain();
        return new RasterImage(shared, sourceFormat);
    }

    public synchronized BufferedImage borrowBuffer() {
        ensureOpen();
        return shared.buffer;
    }

    public int width() {
        return borrowBuffer().getWidth();
    }

    public int height() {
        return borrowBuffer().getHeight();
    }

    public boolean hasAlpha() {
        return borrowBuffer().getColorModel().hasAlpha();
    }

    public ImageFormat sourceFormat() {
        return sourceFormat;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            shared.release();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RasterImage is closed");
        }
    }

    private static final class SharedRaster {
        private final BufferedImage buffer;

        private final AtomicInteger references = new AtomicInteger(1);

        private SharedRaster(BufferedImage buffer) {
            this.buffer = buffer;
        }

        private void retain() {
            if (references.incrementAndGet() <= 1) {
                throw new IllegalStateException("RasterImage is released");
            }
        }

        private void release() {
            if (references.decrementAndGet() == 0) {
                buffer.flush();
            }
        }
    }
}
