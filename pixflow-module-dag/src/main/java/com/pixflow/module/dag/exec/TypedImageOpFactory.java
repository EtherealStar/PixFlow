package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.SetBackgroundSpec;
import com.pixflow.infra.image.op.impl.CompressOp;
import com.pixflow.infra.image.op.impl.ConvertFormatOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
import com.pixflow.infra.image.op.impl.SetBackgroundOp;
import java.util.Objects;

/** 将编译期类型化 spec 绑定到 infra-image 的真实操作实现。 */
public final class TypedImageOpFactory {
    public interface WatermarkResolver {
        ImageOp resolve(WatermarkBindingSpec spec);
    }

    public interface BackgroundResolver {
        ImageOp resolve(SetBackgroundBindingSpec spec);
    }

    private final WatermarkResolver watermarkResolver;

    private final BackgroundResolver backgroundResolver;

    public TypedImageOpFactory(WatermarkResolver watermarkResolver, BackgroundResolver backgroundResolver) {
        this.watermarkResolver = Objects.requireNonNull(watermarkResolver);
        this.backgroundResolver = Objects.requireNonNull(backgroundResolver);
    }

    public ImageOp create(LocalImageStep step) {
        return switch (step.typedSpec()) {
            case LocalImageBindingSpec.Resize spec -> new ResizeOp(spec.value());
            case LocalImageBindingSpec.Compress spec -> new CompressOp(spec.value());
            case LocalImageBindingSpec.SetBackground spec -> background(spec.value());
            case LocalImageBindingSpec.Watermark spec -> watermarkResolver.resolve(spec.value());
            case LocalImageBindingSpec.ConvertFormat spec -> new ConvertFormatOp(spec.value());
        };
    }

    private ImageOp background(SetBackgroundBindingSpec spec) {
        return spec.imageRef() == null
                ? new SetBackgroundOp(new SetBackgroundSpec(spec.color(), null, spec.fit()))
                : backgroundResolver.resolve(spec);
    }

}
