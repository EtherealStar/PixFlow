package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.WatermarkSpec;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class WatermarkOp implements ImageOp {
    private final WatermarkSpec spec;

    public WatermarkOp(WatermarkSpec spec) {
        this.spec = spec;
    }

    @Override
    public RasterImage apply(RasterImage src) {
        try {
            BufferedImage output = new BufferedImage(src.width(), src.height(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = output.createGraphics();
            try {
                g.drawImage(src.borrowBuffer(), 0, 0, null);
                int watermarkWidth = Math.max(1, (int) Math.round(src.width() * spec.scale()));
                int watermarkHeight = Math.max(
                        1,
                        (int) Math.round(
                                spec.watermark().height()
                                        * (watermarkWidth / (double) spec.watermark().width())));
                Point point = point(src.width(), src.height(), watermarkWidth, watermarkHeight);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, spec.opacity()));
                g.drawImage(spec.watermark().borrowBuffer(), point.x, point.y, watermarkWidth, watermarkHeight, null);
            } finally {
                g.dispose();
            }
            return RasterImage.takeOwnership(output, src.sourceFormat());
        } catch (RuntimeException ex) {
            throw new ImageProcessingException(
                    ImageProcessingException.Reason.INVALID_OP_PARAM,
                    src.sourceFormat(),
                    src.width(),
                    src.height(),
                    "水印操作失败",
                    ex);
        }
    }

    private Point point(int baseWidth, int baseHeight, int watermarkWidth, int watermarkHeight) {
        int left = spec.margin();
        int centerX = (baseWidth - watermarkWidth) / 2;
        int right = baseWidth - watermarkWidth - spec.margin();
        int top = spec.margin();
        int centerY = (baseHeight - watermarkHeight) / 2;
        int bottom = baseHeight - watermarkHeight - spec.margin();
        return switch (spec.position()) {
            case TOP_LEFT -> new Point(left, top);
            case TOP_CENTER -> new Point(centerX, top);
            case TOP_RIGHT -> new Point(right, top);
            case CENTER_LEFT -> new Point(left, centerY);
            case CENTER -> new Point(centerX, centerY);
            case CENTER_RIGHT -> new Point(right, centerY);
            case BOTTOM_LEFT -> new Point(left, bottom);
            case BOTTOM_CENTER -> new Point(centerX, bottom);
            case BOTTOM_RIGHT -> new Point(right, bottom);
        };
    }

    private record Point(int x, int y) {
    }
}
