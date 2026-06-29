package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.expand.ImageDescriptor;
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
    CopyContext copyContext
) {
    public UnitInput {
        imageDescriptors = imageDescriptors == null ? List.of() : List.copyOf(imageDescriptors);
        // copyContext 可空
    }

    public static UnitInput images(List<ImageDescriptor> images) {
        return new UnitInput(images, null);
    }

    public static UnitInput copy(CopyContext context) {
        return new UnitInput(List.of(), Objects.requireNonNull(context, "copyContext"));
    }
}