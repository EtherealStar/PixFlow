package com.pixflow.infra.image.op;

import com.pixflow.infra.image.RasterImage;
import java.util.List;

@FunctionalInterface
public interface MultiImageOp {
    RasterImage apply(List<RasterImage> members);
}
