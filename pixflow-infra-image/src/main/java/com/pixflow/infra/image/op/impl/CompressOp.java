package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ImageOp;

public class CompressOp implements ImageOp {
    private final CompressSpec spec;

    public CompressOp(CompressSpec spec) {
        this.spec = spec;
    }

    @Override
    public RasterImage apply(RasterImage src) {
        // 压缩是最终编码阶段的质量/体积策略；这里保持栅格不变，避免中间反复编解码。
        return src;
    }

    public CompressSpec spec() {
        return spec;
    }
}
