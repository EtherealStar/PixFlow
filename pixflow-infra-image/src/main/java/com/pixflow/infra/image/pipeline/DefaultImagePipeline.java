package com.pixflow.infra.image.pipeline;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.budget.PixelBudget;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.MultiImageOp;
import com.pixflow.infra.image.op.impl.CompressOp;
import com.pixflow.infra.image.op.impl.ConvertFormatOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DefaultImagePipeline implements ImagePipeline {
    private final ImageCodec codec;
    private final PixelBudget pixelBudget;
    private final long maxSourcePixels;
    private final int maxDimension;
    private final double targetHeadroomFactor;
    private final Duration acquireTimeout;

    public DefaultImagePipeline(ImageCodec codec, PixelBudget pixelBudget, long maxSourcePixels,
                                int maxDimension, double targetHeadroomFactor, Duration acquireTimeout) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.pixelBudget = Objects.requireNonNull(pixelBudget, "pixelBudget must not be null");
        this.maxSourcePixels = maxSourcePixels;
        this.maxDimension = maxDimension;
        this.targetHeadroomFactor = targetHeadroomFactor;
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout must not be null");
    }

    @Override
    public byte[] run(ReopenableImageSource source, List<ImageOp> ops, EncodeSpec encode) {
        ImageProbe probe = probe(source);
        long sourcePixels = pixels(probe);
        long weightedPixels = weightedPixels(sourcePixels, targetPixels(sourcePixels, ops));
        // 许可覆盖 decode、全部像素操作和 encode，异常也必须在同一作用域释放。
        try (PixelBudget.Permit ignored = pixelBudget.acquire(weightedPixels, acquireTimeout);
             InputStream decodeStream = source.openStream()) {
            RasterImage image = codec.decode(decodeStream);
            PipelineResult result = null;
            try {
                result = applyOps(image, ops);
                return codec.encode(result.image(), result.encodeSpec(encode));
            } finally {
                (result == null ? image : result.image()).close();
            }
        } catch (IOException io) {
            throw new ImageProcessingException(ImageProcessingException.Reason.DECODE_FAILED,
                    probe.format(), probe.width(), probe.height(), "failed to close image source", io);
        }
    }

    @Override
    public byte[] runComposed(
            List<ReopenableImageSource> members,
            List<ImageOp> perMemberOps,
            MultiImageOp compose,
            List<ImageOp> postOps,
            EncodeSpec encode) {
        List<ImageProbe> probes = members.stream().map(this::probe).toList();
        long sourcePixels = probes.stream().mapToLong(this::pixels).reduce(0L, Math::addExact);
        long maxMemberPixels = probes.stream().mapToLong(this::pixels).max().orElseThrow();
        // 未知 compose 布局时按“成员数 × 最大成员面积”估算画布，宁可提前拒绝也不能低估峰值。
        long composeUpperBound = Math.multiplyExact(maxMemberPixels, probes.size());
        long memberTargetPixels = members.size() * targetPixels(maxMemberPixels, perMemberOps);
        long admissionPixels = Math.addExact(sourcePixels, Math.max(composeUpperBound, memberTargetPixels));
        try (PixelBudget.Permit ignored = pixelBudget.acquire(weightedPixels(sourcePixels, admissionPixels), acquireTimeout)) {
            List<RasterImage> processed = new ArrayList<>();
            RasterImage composed = null;
            PipelineResult result = null;
            try {
                for (ReopenableImageSource member : members) {
                    try (InputStream stream = member.openStream()) {
                        RasterImage decoded = codec.decode(stream);
                        processed.add(applyOps(decoded, perMemberOps).image());
                    } catch (IOException io) {
                        throw new ImageProcessingException(ImageProcessingException.Reason.DECODE_FAILED,
                                null, null, null, "failed to close image source", io);
                    }
                }
                composed = compose.apply(processed);
                boolean aliasesMember = false;
                for (RasterImage member : processed) {
                    aliasesMember |= member == composed;
                }
                if (aliasesMember) {
                    composed = composed.retain();
                }
                closeAll(processed);
                processed.clear();
                result = applyOps(composed, postOps);
                composed = null; // ownership transferred to applyOps/result
                return codec.encode(result.image(), result.encodeSpec(encode));
            } finally {
                closeAll(processed);
                if (composed != null) composed.close();
                if (result != null) result.image().close();
            }
        }
    }

    private ImageProbe probe(ReopenableImageSource source) {
        try (InputStream stream = source.openStream()) {
            return codec.probe(stream);
        } catch (IOException io) {
            throw new ImageProcessingException(ImageProcessingException.Reason.DECODE_FAILED,
                    null, null, null, "failed to close image source", io);
        }
    }

    private long pixels(ImageProbe probe) {
        long pixels = Math.multiplyExact((long) probe.width(), probe.height());
        if (pixels > maxSourcePixels || probe.width() > maxDimension || probe.height() > maxDimension) {
            throw new ImageProcessingException(ImageProcessingException.Reason.SOURCE_TOO_LARGE,
                    probe.format(), probe.width(), probe.height(), "image dimensions exceed configured limits");
        }
        return pixels;
    }

    private long weightedPixels(long sourcePixels, long targetPixels) {
        return Math.addExact(sourcePixels, (long) Math.ceil(targetPixels * targetHeadroomFactor));
    }

    private long targetPixels(long sourcePixels, List<ImageOp> ops) {
        long target = sourcePixels;
        for (ImageOp op : ops == null ? List.<ImageOp>of() : ops) {
            if (op instanceof ResizeOp resize) {
                var spec = resize.spec();
                if (spec.width() != null && spec.height() != null) {
                    target = Math.max(target, Math.multiplyExact((long) spec.width(), spec.height()));
                }
            }
        }
        return target;
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
            RasterImage previous = current;
            try {
                current = op.apply(previous);
            } catch (RuntimeException failure) {
                previous.close();
                throw failure;
            }
            if (current != previous) {
                previous.close();
            }
        }
        return new PipelineResult(current, convert, compress);
    }

    private void closeAll(List<RasterImage> images) {
        images.forEach(RasterImage::close);
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
