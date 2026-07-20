package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.harness.state.model.UnitKey;
import java.util.List;
import java.util.Objects;

/**
 * 单元输入:由 task worker 喂入的中立数据(对齐 dag.md §8.1)。
 *
 * <p>普通支路:imageDescriptors 含 1 张图(单张成员)。
 * 组支路:imageDescriptors 含 N 个分组成员(groupKey/viewId 已填充)。
 * 文案支路:copyContext 非空,imageDescriptors 为空。
 */
public record UnitInput(
    List<ImageDescriptor> imageDescriptors,
    CopyContext copyContext,
    String outputObjectKey,
    UnitKey unitKey,
    long runEpoch
) {
    public UnitInput(List<ImageDescriptor> imageDescriptors, CopyContext copyContext) {
        this(imageDescriptors, copyContext, null, null, 0);
    }

    public UnitInput {
        imageDescriptors = imageDescriptors == null ? List.of() : List.copyOf(imageDescriptors);
        // copyContext 可空
    }

    public static UnitInput images(List<ImageDescriptor> images) {
        return new UnitInput(images, null, "test/output.jpg", null, 0);
    }

    public static UnitInput images(List<ImageDescriptor> images, String outputObjectKey) {
        return new UnitInput(images, null, Objects.requireNonNull(outputObjectKey, "outputObjectKey"), null, 0);
    }

    public static UnitInput images(List<ImageDescriptor> images, String outputObjectKey,
                                   UnitKey unitKey, long runEpoch) {
        if (runEpoch <= 0) {
            throw new IllegalArgumentException("runEpoch must be positive");
        }
        return new UnitInput(images, null, Objects.requireNonNull(outputObjectKey, "outputObjectKey"),
                Objects.requireNonNull(unitKey, "unitKey"), runEpoch);
    }

    public static UnitInput copy(CopyContext context) {
        return new UnitInput(List.of(), Objects.requireNonNull(context, "copyContext"), null, null, 0);
    }
}
