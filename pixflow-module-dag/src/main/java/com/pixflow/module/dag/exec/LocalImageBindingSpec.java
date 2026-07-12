package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.op.CompressSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.module.dag.ir.PixelTool;

/** 本地图像步骤允许的强类型编译产物全集。 */
public sealed interface LocalImageBindingSpec permits LocalImageBindingSpec.Resize,
        LocalImageBindingSpec.Compress, LocalImageBindingSpec.SetBackground,
        LocalImageBindingSpec.Watermark, LocalImageBindingSpec.ConvertFormat {
    record Resize(ResizeSpec value) implements LocalImageBindingSpec {}
    record Compress(CompressSpec value) implements LocalImageBindingSpec {}
    record SetBackground(SetBackgroundBindingSpec value) implements LocalImageBindingSpec {}
    record Watermark(WatermarkBindingSpec value) implements LocalImageBindingSpec {}
    record ConvertFormat(ConvertFormatSpec value) implements LocalImageBindingSpec {}

    static LocalImageBindingSpec from(PixelTool tool, Object spec) {
        return switch (tool) {
            case RESIZE -> new Resize((ResizeSpec) spec);
            case COMPRESS -> new Compress((CompressSpec) spec);
            case SET_BACKGROUND -> new SetBackground((SetBackgroundBindingSpec) spec);
            case WATERMARK -> new Watermark((WatermarkBindingSpec) spec);
            case CONVERT_FORMAT -> new ConvertFormat((ConvertFormatSpec) spec);
            default -> throw new IllegalArgumentException("非本地图像 spec: " + tool);
        };
    }
}
