package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.ResizeSpec;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import net.coobird.thumbnailator.Thumbnails;

public class ResizeOp implements ImageOp {
    private final ResizeSpec spec;

    public ResizeOp(ResizeSpec spec) {
        this.spec = spec;
    }

    public ResizeSpec spec() {
        return spec;
    }

    @Override
    public RasterImage apply(RasterImage src) {
        try {
            Dimensions dims = dimensions(src);
            BufferedImage resized = switch (spec.mode()) {
                case EXACT -> Thumbnails.of(src.borrowBuffer()).forceSize(dims.width, dims.height).asBufferedImage();
                case FIT -> Thumbnails.of(src.borrowBuffer())
                        .size(dims.width, dims.height)
                        .keepAspectRatio(true)
                        .asBufferedImage();
                case FILL -> fill(src, dims.width, dims.height);
            };
            return RasterImage.takeOwnership(resized, src.sourceFormat());
        } catch (IOException | RuntimeException ex) {
            throw new ImageProcessingException(
                    ImageProcessingException.Reason.INVALID_OP_PARAM,
                    src.sourceFormat(),
                    src.width(),
                    src.height(),
                    "缩放操作失败",
                    ex);
        }
    }

    private Dimensions dimensions(RasterImage src) {
        int targetWidth = spec.width() != null
                ? spec.width()
                : Math.round(src.width() * (spec.height() / (float) src.height()));
        int targetHeight = spec.height() != null
                ? spec.height()
                : Math.round(src.height() * (spec.width() / (float) src.width()));
        if (!spec.upscale()) {
            targetWidth = Math.min(targetWidth, src.width());
            targetHeight = Math.min(targetHeight, src.height());
        }
        return new Dimensions(Math.max(1, targetWidth), Math.max(1, targetHeight));
    }

    private BufferedImage fill(RasterImage src, int targetWidth, int targetHeight) throws IOException {
        double scale = Math.max(targetWidth / (double) src.width(), targetHeight / (double) src.height());
        int scaledWidth = Math.max(1, (int) Math.round(src.width() * scale));
        int scaledHeight = Math.max(1, (int) Math.round(src.height() * scale));
        BufferedImage scaled = Thumbnails.of(src.borrowBuffer())
                .forceSize(scaledWidth, scaledHeight)
                .asBufferedImage();
        try {
            int x = Math.max(0, (scaledWidth - targetWidth) / 2);
            int y = Math.max(0, (scaledHeight - targetHeight) / 2);
            BufferedImage cropped = scaled.getSubimage(
                    x,
                    y,
                    Math.min(targetWidth, scaledWidth - x),
                    Math.min(targetHeight, scaledHeight - y));
            BufferedImage copy = new BufferedImage(
                    targetWidth,
                    targetHeight,
                    src.hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            try {
                g.drawImage(cropped, 0, 0, null);
                return copy;
            } finally {
                g.dispose();
            }
        } finally {
            scaled.flush();
        }
    }

    private record Dimensions(int width, int height) {
    }
}
