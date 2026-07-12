package com.pixflow.infra.image.op;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.impl.ComposeGroupOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
import com.pixflow.infra.image.op.impl.SetBackgroundOp;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageOpsTest {

    @Test
    void resizeFitDoesNotUpscaleWhenDisabled() {
        RasterImage src = image(10, 8, Color.RED, false);

        RasterImage out = new ResizeOp(new ResizeSpec(100, 100, ResizeSpec.Mode.FIT, false)).apply(src);

        assertThat(out.width()).isEqualTo(10);
        assertThat(out.height()).isEqualTo(8);
    }

    @Test
    void setBackgroundRemovesAlpha() {
        RasterImage src = image(6, 6, new Color(255, 0, 0, 128), true);

        RasterImage out = new SetBackgroundOp(new SetBackgroundSpec(Color.WHITE, null, null)).apply(src);

        assertThat(out.hasAlpha()).isFalse();
        assertThat(out.width()).isEqualTo(6);
    }

    @Test
    void composeGridUsesStableSquareLayout() {
        RasterImage first = image(10, 10, Color.RED, false);
        RasterImage second = image(10, 10, Color.GREEN, false);
        RasterImage third = image(10, 10, Color.BLUE, false);

        RasterImage out = new ComposeGroupOp(new ComposeSpec(ComposeSpec.Layout.GRID, List.of(), 2, Color.WHITE))
                .apply(List.of(first, second, third));

        assertThat(out.width()).isEqualTo(22);
        assertThat(out.height()).isEqualTo(22);
    }

    private RasterImage image(int width, int height, Color color, boolean alpha) {
        BufferedImage buffered = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = buffered.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return RasterImage.takeOwnership(buffered, ImageFormat.PNG);
    }
}
