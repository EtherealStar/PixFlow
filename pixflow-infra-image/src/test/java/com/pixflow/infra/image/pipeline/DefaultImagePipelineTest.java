package com.pixflow.infra.image.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.budget.DefaultPixelBudget;
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
        DefaultImagePipeline pipeline = pipeline(codec);
        ImageOp op = src -> RasterImage.takeOwnership(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), src.sourceFormat());

        byte[] bytes = pipeline.run(source(), List.of(op), EncodeSpec.of(ImageFormat.PNG));

        assertThat(bytes).containsExactly(9, 9, 9);
        assertThat(codec.decodeCalls).isEqualTo(1);
        assertThat(codec.encodeCalls).isEqualTo(1);
        assertThat(codec.probeCalls).isEqualTo(1);
        assertThat(codec.probedBeforeDecode).isTrue();
    }

    @Test
    void compressStepIsMergedIntoFinalEncodeSpec() {
        CountingCodec codec = new CountingCodec();
        DefaultImagePipeline pipeline = pipeline(codec);

        pipeline.run(
                source(),
                List.of(new CompressOp(new CompressSpec(70, null))),
                EncodeSpec.of(ImageFormat.JPEG));

        assertThat(codec.lastSpec.quality()).isEqualTo(70);
    }

    @Test
    void composedPipelineProbesEveryMemberBeforeFirstDecode() {
        CountingCodec codec = new CountingCodec();
        DefaultImagePipeline pipeline = pipeline(codec);

        pipeline.runComposed(List.of(source(), source()), List.of(), images -> images.get(0),
                List.of(), EncodeSpec.of(ImageFormat.PNG));

        assertThat(codec.probeCalls).isEqualTo(2);
        assertThat(codec.decodeCalls).isEqualTo(2);
        assertThat(codec.allMembersProbedBeforeDecode).isTrue();
    }

    @Test
    void encodeFailureReleasesPixelPermit() {
        CountingCodec codec = new CountingCodec();
        codec.failEncode = true;
        DefaultImagePipeline pipeline = pipeline(codec);

        assertThatThrownBy(() -> pipeline.run(source(), List.of(), EncodeSpec.of(ImageFormat.PNG)))
                .isInstanceOf(RuntimeException.class);

        codec.failEncode = false;
        assertThat(pipeline.run(source(), List.of(), EncodeSpec.of(ImageFormat.PNG)))
                .containsExactly(9, 9, 9);
    }

    private static DefaultImagePipeline pipeline(CountingCodec codec) {
        return new DefaultImagePipeline(codec, new DefaultPixelBudget(1_000),
                100, 100, 1.25, java.time.Duration.ofMillis(50));
    }

    private static ReopenableImageSource source() {
        return () -> new ByteArrayInputStream(new byte[] {1});
    }

    private static class CountingCodec implements ImageCodec {
        int decodeCalls;
        int encodeCalls;
        int probeCalls;
        boolean probedBeforeDecode;
        boolean allMembersProbedBeforeDecode = true;
        boolean failEncode;
        EncodeSpec lastSpec;

        @Override
        public ImageProbe probe(InputStream data) {
            probeCalls++;
            return new ImageProbe(ImageFormat.PNG, 4, 4, false);
        }

        @Override
        public RasterImage decode(InputStream data) {
            probedBeforeDecode = probeCalls > 0;
            allMembersProbedBeforeDecode &= probeCalls >= 2;
            decodeCalls++;
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.RED);
                g.fillRect(0, 0, 4, 4);
            } finally {
                g.dispose();
            }
            return RasterImage.takeOwnership(image, ImageFormat.PNG);
        }

        @Override
        public byte[] encode(RasterImage image, EncodeSpec spec) {
            encodeCalls++;
            lastSpec = spec;
            if (failEncode) {
                throw new IllegalStateException("encode failed");
            }
            return new byte[] {9, 9, 9};
        }
    }
}
