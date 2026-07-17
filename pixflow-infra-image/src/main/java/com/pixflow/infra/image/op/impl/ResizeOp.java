package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.geometry.RasterDimensions;
import com.pixflow.infra.image.geometry.ResizeGeometry;
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
            RasterDimensions dims = ResizeGeometry.resolve(
                    new RasterDimensions(src.width(), src.height()),
                    spec);
            BufferedImage resized = switch (spec.mode()) {
                case EXACT, FIT -> Thumbnails.of(src.borrowBuffer())
                        .forceSize(dims.intWidth(), dims.intHeight())
                        .asBufferedImage();
                case FILL -> fill(src, dims.intWidth(), dims.intHeight());
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

    private BufferedImage fill(RasterImage src, int targetWidth, int targetHeight) throws IOException {
        RasterDimensions scaledDimensions = ResizeGeometry.fillIntermediate(
                new RasterDimensions(src.width(), src.height()),
                new RasterDimensions(targetWidth, targetHeight));
        int scaledWidth = scaledDimensions.intWidth();
        int scaledHeight = scaledDimensions.intHeight();
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
}
