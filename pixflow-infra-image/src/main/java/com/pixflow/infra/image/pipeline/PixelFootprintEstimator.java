package com.pixflow.infra.image.pipeline;

import com.pixflow.infra.image.ImageProbe;
import com.pixflow.infra.image.geometry.ComposeGeometry;
import com.pixflow.infra.image.geometry.RasterDimensions;
import com.pixflow.infra.image.geometry.ResizeGeometry;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.MultiImageOp;
import com.pixflow.infra.image.op.impl.ComposeGroupOp;
import com.pixflow.infra.image.op.impl.CompressOp;
import com.pixflow.infra.image.op.impl.ConvertFormatOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
import com.pixflow.infra.image.op.impl.SetBackgroundOp;
import com.pixflow.infra.image.op.impl.WatermarkOp;
import java.util.ArrayList;
import java.util.List;

final class PixelFootprintEstimator {
    private final double targetHeadroomFactor;

    PixelFootprintEstimator(double targetHeadroomFactor) {
        this.targetHeadroomFactor = targetHeadroomFactor;
    }

    long estimateSingle(ImageProbe source, List<ImageOp> ops) {
        RasterDimensions sourceDimensions = dimensions(source);
        Projection projection = project(sourceDimensions, ops, true);
        return weighted(sourceDimensions.pixels(), projection.peakTargets());
    }

    long estimateComposed(
            List<ImageProbe> sources,
            List<ImageOp> perMemberOps,
            MultiImageOp compose,
            List<ImageOp> postOps) {
        if (!(compose instanceof ComposeGroupOp composeGroup)) {
            throw new IllegalArgumentException("composed pipeline requires a geometry-aware compose operation");
        }
        long sourcePixels = 0L;
        long memberPixels = 0L;
        long memberProcessingPeak = 0L;
        List<RasterDimensions> memberDimensions = new ArrayList<>();
        for (ImageProbe source : sources) {
            RasterDimensions sourceDimensions = dimensions(source);
            sourcePixels = Math.addExact(sourcePixels, sourceDimensions.pixels());
            Projection projection = project(sourceDimensions, perMemberOps, true);
            memberProcessingPeak = Math.max(
                    memberProcessingPeak,
                    Math.addExact(memberPixels, projection.peakTargets()));
            memberDimensions.add(projection.output());
            memberPixels = Math.addExact(memberPixels, projection.output().pixels());
        }

        RasterDimensions canvas = ComposeGeometry.resolve(memberDimensions, composeGroup.spec());
        long peakTargets = Math.max(memberProcessingPeak, Math.addExact(memberPixels, canvas.pixels()));
        Projection postProjection = project(canvas, postOps, false);
        peakTargets = Math.max(peakTargets, postProjection.peakTargets());
        return weighted(sourcePixels, peakTargets);
    }

    private Projection project(RasterDimensions source, List<ImageOp> ops, boolean sourceCoveredByBase) {
        RasterDimensions current = source;
        long peakTargets = sourceCoveredByBase ? source.pixels() : 0L;
        boolean currentCoveredByBase = sourceCoveredByBase;
        for (ImageOp op : safe(ops)) {
            if (op instanceof ResizeOp resize) {
                RasterDimensions output = ResizeGeometry.resolve(current, resize.spec());
                long allocated = output.pixels();
                if (resize.spec().mode() == com.pixflow.infra.image.op.ResizeSpec.Mode.FILL) {
                    RasterDimensions intermediate = ResizeGeometry.fillIntermediate(current, output);
                    allocated = Math.addExact(allocated, intermediate.pixels());
                }
                if (!currentCoveredByBase) {
                    allocated = Math.addExact(current.pixels(), allocated);
                }
                peakTargets = Math.max(peakTargets, allocated);
                current = output;
                currentCoveredByBase = false;
                continue;
            }
            if (op instanceof SetBackgroundOp || op instanceof WatermarkOp) {
                long allocated = current.pixels();
                if (!currentCoveredByBase) {
                    allocated = Math.addExact(current.pixels(), allocated);
                }
                peakTargets = Math.max(peakTargets, allocated);
                currentCoveredByBase = false;
                continue;
            }
            if (op instanceof CompressOp || op instanceof ConvertFormatOp) {
                continue;
            }
            throw new IllegalArgumentException("pipeline contains an operation with unknown raster footprint");
        }
        return new Projection(current, peakTargets);
    }

    private long weighted(long sourcePixels, long targetPixels) {
        double weightedTarget = Math.ceil(targetPixels * targetHeadroomFactor);
        if (!Double.isFinite(weightedTarget) || weightedTarget > Long.MAX_VALUE) {
            throw new ArithmeticException("weighted target pixels overflow");
        }
        return Math.addExact(sourcePixels, (long) weightedTarget);
    }

    private static RasterDimensions dimensions(ImageProbe probe) {
        return new RasterDimensions(probe.width(), probe.height());
    }

    private static List<ImageOp> safe(List<ImageOp> ops) {
        return ops == null ? List.of() : ops;
    }

    private record Projection(RasterDimensions output, long peakTargets) {
    }
}
