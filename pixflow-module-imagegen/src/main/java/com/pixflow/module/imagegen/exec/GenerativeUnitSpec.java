package com.pixflow.module.imagegen.exec;

import com.pixflow.infra.storage.ObjectLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 生成式单元的执行载荷(对齐 imagegen.md §8.2)。
 *
 * <p>由 {@code module/task} 在 fan-out 时按 ImagegenPlan.sourceImageIds 逐单元构造:
 * 一个源图 ID 对应一个 {@code GenerativeUnitSpec},作为 {@link ImageGenExecutor#redraw} 的入参。
 *
 * <p>字段说明:
 * <ul>
 *   <li>{@code taskId}:所属 process_task ID(用于落桶 key 与日志关联)</li>
 *   <li>{@code skuId}:所属 SKU ID(源图所属 SKU)</li>
 *   <li>{@code sourceImageId}:源图 ID</li>
 *   <li>{@code sourceLocation}:源图在 PACKAGES 桶的位置(task 从 asset_image 取得后传入)</li>
 *   <li>{@code prompt}:本提案的生图提示词(全单元共享)</li>
 *   <li>{@code params}:本提案的生图参数(全单元共享)</li>
 *   <li>{@code outputExt}:输出格式扩展名(默认 png,来自配置)</li>
 * </ul>
 */
public record GenerativeUnitSpec(
        String taskId,
        String unitKeyHash,
        long runEpoch,
        String skuId,
        String sourceImageId,
        ObjectLocation sourceLocation,
        String prompt,
        Map<String, Object> params,
        String outputExt) {

    public GenerativeUnitSpec {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(unitKeyHash, "unitKeyHash");
        if (runEpoch <= 0) throw new IllegalArgumentException("runEpoch must be positive");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(sourceImageId, "sourceImageId");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(outputExt, "outputExt");
        params = params == null || params.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
