package com.pixflow.module.vision.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.impl.ConvertFormatOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.module.vision.analyze.VisionImageRef;
import com.pixflow.module.vision.config.VisionProperties;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisionImagePreprocessorTest {

    @Test
    void preprocessUsesResizeAndConvertThenBuildsImagePart() {
        RecordingPipeline pipeline = new RecordingPipeline();
        VisionImagePreprocessor preprocessor = new VisionImagePreprocessor(pipeline, new VisionProperties());

        PreparedVisionImage prepared = preprocessor.preprocess(new ResolvedVisionImage(
                VisionImageRef.of(BucketType.PACKAGES, "1/images/a.png", "sku", "main", "front"),
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3}),
                3L,
                "image/png"));

        assertThat(prepared.encodedBytes()).isEqualTo(3);
        assertThat(pipeline.ops).hasSize(2);
        assertThat(pipeline.ops.get(0)).isInstanceOf(ResizeOp.class);
        assertThat(pipeline.ops.get(1)).isInstanceOf(ConvertFormatOp.class);
        assertThat(pipeline.encode.quality()).isEqualTo(85);
    }

    private static class RecordingPipeline implements ImagePipeline {
        List<ImageOp> ops;
        EncodeSpec encode;

        @Override
        public byte[] run(ReopenableImageSource source, List<ImageOp> ops, EncodeSpec encode) {
            this.ops = ops;
            this.encode = encode;
            return new byte[] {9, 9, 9};
        }

        @Override
        public byte[] runComposed(
                List<ReopenableImageSource> members,
                List<ImageOp> perMemberOps,
                com.pixflow.infra.image.op.MultiImageOp compose,
                List<ImageOp> postOps,
                EncodeSpec encode) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
