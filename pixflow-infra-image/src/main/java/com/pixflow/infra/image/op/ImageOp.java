package com.pixflow.infra.image.op;

import com.pixflow.infra.image.RasterImage;

@FunctionalInterface
public interface ImageOp {
    RasterImage apply(RasterImage src);
}
