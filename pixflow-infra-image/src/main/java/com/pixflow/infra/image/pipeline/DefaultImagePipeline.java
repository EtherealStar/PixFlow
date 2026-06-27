package com.pixflow.infra.image.pipeline;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.MultiImageOp;
import com.pixflow.infra.image.op.impl.CompressOp;
import com.pixflow.infra.image.op.impl.ConvertFormatOp;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DefaultImagePipeline implements ImagePipeline {
    private final ImageCodec codec;

    public DefaultImagePipeline(ImageCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    @Override
    public byte[] run(InputStream source, List<ImageOp> ops, EncodeSpec encode) {
        RasterImage image = codec.decode(source);
        PipelineResult result = applyOps(image, ops);
        return codec.encode(result.image(), result.encodeSpec(encode));
    }

    @Override
    public byte[] runComposed(
            List<InputStream> members,
            List<ImageOp> perMemberOps,
            MultiImageOp compose,
            List<ImageOp> postOps,
            EncodeSpec encode) {
        List<RasterImage> processed = new ArrayList<>();
        for (InputStream member : members) {
            PipelineResult result = applyOps(codec.decode(member), perMemberOps);
            processed.add(result.image());
        }
        RasterImage composed = compose.apply(processed);
        PipelineResult result = applyOps(composed, postOps);
        return codec.encode(result.image(), result.encodeSpec(encode));
    }

    private PipelineResult applyOps(RasterImage start, List<ImageOp> ops) {
        RasterImage current = start;
        ConvertFormatSpec convert = null;
        CompressSpec compress = null;
        for (ImageOp op : ops == null ? List.<ImageOp>of() : ops) {
            if (op instanceof ConvertFormatOp convertOp) {
                convert = convertOp.spec();
                continue;
            }
            if (op instanceof CompressOp compressOp) {
                compress = compressOp.spec();
                continue;
            }
            current = op.apply(current);
        }
        return new PipelineResult(current, convert, compress);
    }

    private record PipelineResult(RasterImage image, ConvertFormatSpec convert, CompressSpec compress) {
        private EncodeSpec encodeSpec(EncodeSpec fallback) {
            EncodeSpec base = convert != null ? convert.toEncodeSpec() : fallback;
            if (compress == null) {
                return base;
            }
            return new EncodeSpec(
                    base.targetFormat(),
                    compress.quality() != null ? compress.quality() : base.quality(),
                    compress.targetBytes(),
                    base.flattenBackground());
        }
    }
}
