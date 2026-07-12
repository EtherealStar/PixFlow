package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.SetBackgroundSpec;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class SetBackgroundOp implements ImageOp {
    private final SetBackgroundSpec spec;

    public SetBackgroundOp(SetBackgroundSpec spec) {
        this.spec = spec;
    }

    @Override
    public RasterImage apply(RasterImage src) {
        try {
            BufferedImage output = new BufferedImage(src.width(), src.height(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = output.createGraphics();
            try {
                drawBackground(g, src.width(), src.height());
                g.drawImage(src.borrowBuffer(), 0, 0, null);
            } finally {
                g.dispose();
            }
            return RasterImage.takeOwnership(output, src.sourceFormat());
        } catch (RuntimeException ex) {
            throw new ImageProcessingException(ImageProcessingException.Reason.INVALID_OP_PARAM, src.sourceFormat(), src.width(), src.height(), "背景合成失败", ex);
        }
    }

    private void drawBackground(Graphics2D g, int width, int height) {
        if (spec.background() == null) {
            g.setColor(spec.color());
            g.fillRect(0, 0, width, height);
            return;
        }
        BufferedImage bg = spec.background().borrowBuffer();
        switch (spec.fit()) {
            case STRETCH -> g.drawImage(bg, 0, 0, width, height, null);
            case CENTER -> g.drawImage(bg, (width - bg.getWidth()) / 2, (height - bg.getHeight()) / 2, null);
            case TILE -> {
                for (int y = 0; y < height; y += bg.getHeight()) {
                    for (int x = 0; x < width; x += bg.getWidth()) {
                        g.drawImage(bg, x, y, null);
                    }
                }
            }
        }
    }
}
