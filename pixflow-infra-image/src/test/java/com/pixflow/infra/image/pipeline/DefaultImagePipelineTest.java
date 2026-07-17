package com.pixflow.infra.image.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.budget.DefaultPixelBudget;
import com.pixflow.infra.image.op.ComposeSpec;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.infra.image.op.impl.CompressOp;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.impl.ComposeGroupOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
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
        ImageOp op = new ResizeOp(new ResizeSpec(4, 4, ResizeSpec.Mode.EXACT, false));

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

        pipeline.runComposed(List.of(source(), source()), List.of(), new ComposeGroupOp(
                        new ComposeSpec(ComposeSpec.Layout.HORIZONTAL, List.of(), 0, Color.WHITE)),
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

    @Test
    void oneSidedResizeIsRejectedBeforeDecodeWhenProjectedRasterExceedsBudget() {
        CountingCodec codec = new CountingCodec();
        DefaultImagePipeline pipeline = new DefaultImagePipeline(
                codec,
                new DefaultPixelBudget(95),
                100,
                100,
                1.25,
                java.time.Duration.ofMillis(50));

        assertThatThrownBy(() -> pipeline.run(
                source(),
                List.of(new ResizeOp(new ResizeSpec(8, null, ResizeSpec.Mode.FIT, true))),
                EncodeSpec.of(ImageFormat.PNG)))
                .isInstanceOf(ImageProcessingException.class);

        assertThat(codec.decodeCalls).isZero();
    }

    @Test
    void composeGapIsRejectedBeforeAnyMemberDecodeWhenCanvasExceedsBudget() {
        CountingCodec codec = new CountingCodec();
        DefaultImagePipeline pipeline = new DefaultImagePipeline(
                codec,
                new DefaultPixelBudget(121),
                100,
                100,
                1.25,
                java.time.Duration.ofMillis(50));
        ComposeGroupOp compose = new ComposeGroupOp(
                new ComposeSpec(ComposeSpec.Layout.HORIZONTAL, List.of(), 2, Color.WHITE));

        assertThatThrownBy(() -> pipeline.runComposed(
                List.of(source(), source()),
                List.of(),
                compose,
                List.of(),
                EncodeSpec.of(ImageFormat.PNG)))
                .isInstanceOf(ImageProcessingException.class);

        assertThat(codec.decodeCalls).isZero();
    }

    @Test
    void fillIntermediateRasterIsRejectedBeforeDecodeWhenItExceedsBudget() {
        CountingCodec codec = new CountingCodec(100, 1);
        DefaultImagePipeline pipeline = new DefaultImagePipeline(
                codec,
                new DefaultPixelBudget(12_724),
                1_000,
                1_000,
                1.25,
                java.time.Duration.ofMillis(50));

        assertThatThrownBy(() -> pipeline.run(
                source(),
                List.of(new ResizeOp(new ResizeSpec(10, 10, ResizeSpec.Mode.FILL, true))),
                EncodeSpec.of(ImageFormat.PNG)))
                .isInstanceOf(ImageProcessingException.class);

        assertThat(codec.decodeCalls).isZero();
    }

    @Test
    void unknownRasterOperationIsRejectedBeforeDecode() {
        CountingCodec codec = new CountingCodec();

        assertThatThrownBy(() -> pipeline(codec).run(
                source(),
                List.of(image -> image),
                EncodeSpec.of(ImageFormat.PNG)))
                .isInstanceOf(ImageProcessingException.class);

        assertThat(codec.decodeCalls).isZero();
    }

    private static DefaultImagePipeline pipeline(CountingCodec codec) {
        return new DefaultImagePipeline(codec, new DefaultPixelBudget(1_000),
                100, 100, 1.25, java.time.Duration.ofMillis(50));
    }

    private static ReopenableImageSource source() {
        return () -> new ByteArrayInputStream(new byte[] {1});
    }

    private static class CountingCodec implements ImageCodec {
        private final int width;
        private final int height;
        int decodeCalls;
        int encodeCalls;
        int probeCalls;
        boolean probedBeforeDecode;
        boolean allMembersProbedBeforeDecode = true;
        boolean failEncode;
        EncodeSpec lastSpec;

        CountingCodec() {
            this(4, 4);
        }

        CountingCodec(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public ImageProbe probe(InputStream data) {
            probeCalls++;
            return new ImageProbe(ImageFormat.PNG, width, height, false);
        }

        @Override
        public RasterImage decode(InputStream data) {
            probedBeforeDecode = probeCalls > 0;
            allMembersProbedBeforeDecode &= probeCalls >= 2;
            decodeCalls++;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.RED);
                g.fillRect(0, 0, width, height);
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
