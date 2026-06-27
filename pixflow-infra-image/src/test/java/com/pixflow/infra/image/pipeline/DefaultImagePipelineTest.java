package com.pixflow.infra.image.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.impl.CompressOp;
import com.pixflow.infra.image.op.CompressSpec;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultImagePipelineTest {

    @Test
    void decodesOnceAppliesOpsAndEncodesOnce() {
        CountingCodec codec = new CountingCodec();
        DefaultImagePipeline pipeline = new DefaultImagePipeline(codec);
        ImageOp op = src -> RasterImage.of(src.buffer(), src.sourceFormat());

        byte[] bytes = pipeline.run(new ByteArrayInputStream(new byte[] {1}), List.of(op), EncodeSpec.of(ImageFormat.PNG));

        assertThat(bytes).containsExactly(9, 9, 9);
        assertThat(codec.decodeCalls).isEqualTo(1);
        assertThat(codec.encodeCalls).isEqualTo(1);
    }

    @Test
    void compressStepIsMergedIntoFinalEncodeSpec() {
        CountingCodec codec = new CountingCodec();
        DefaultImagePipeline pipeline = new DefaultImagePipeline(codec);

        pipeline.run(
                new ByteArrayInputStream(new byte[] {1}),
                List.of(new CompressOp(new CompressSpec(70, null))),
                EncodeSpec.of(ImageFormat.JPEG));

        assertThat(codec.lastSpec.quality()).isEqualTo(70);
    }

    private static class CountingCodec implements ImageCodec {
        int decodeCalls;
        int encodeCalls;
        EncodeSpec lastSpec;

        @Override
        public ImageProbe probe(InputStream data) {
            return new ImageProbe(ImageFormat.PNG, 4, 4, false);
        }

        @Override
        public RasterImage decode(InputStream data) {
            decodeCalls++;
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.RED);
                g.fillRect(0, 0, 4, 4);
            } finally {
                g.dispose();
            }
            return RasterImage.of(image, ImageFormat.PNG);
        }

        @Override
        public byte[] encode(RasterImage image, EncodeSpec spec) {
            encodeCalls++;
            lastSpec = spec;
            return new byte[] {9, 9, 9};
        }
    }
}
