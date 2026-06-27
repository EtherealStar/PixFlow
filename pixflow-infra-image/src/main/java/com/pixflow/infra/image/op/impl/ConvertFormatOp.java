package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ImageOp;

public class ConvertFormatOp implements ImageOp {
    private final ConvertFormatSpec spec;

    public ConvertFormatOp(ConvertFormatSpec spec) {
        this.spec = spec;
    }

    @Override
    public RasterImage apply(RasterImage src) {
        // 格式转换发生在最终 encode；此操作作为类型化步骤保留转换意图。
        return src;
    }

    public ConvertFormatSpec spec() {
        return spec;
    }
}
