package com.pixflow.module.imagegen.proposal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 规范化后的生图提案(对齐 imagegen.md §5.2 / §十六.11)。
 *
 * <p>记录的是「经过校验 + 规范化」之后的提案事实:
 * <ul>
 *   <li>{@code sourceImageIds} 已按 imageId 字典序排序</li>
 *   <li>{@code prompt} 已 trim</li>
 *   <li>{@code params} 仅含白名单键(白名单外的键在 validator 阶段就被丢弃,不会进入 record)</li>
 *   <li>{@code note} 用户补充文案,不参与 payloadHash</li>
 *   <li>{@code conversationId} / {@code packageId} 关联会话与素材包</li>
 * </ul>
 *
 * <p>本 record 是 imagegen 模块内的事实数据,序列化进 {@code PendingPlanProposal.payloadJson} 持久化;
 * 反序列化时也由 validator 路径重新生成(不会直接信任外部反序列化的产物)。
 */
public record ImagegenPlan(
        List<String> sourceImageIds,
        String prompt,
        Map<String, Object> params,
        String note,
        String conversationId,
        String packageId) {

    public ImagegenPlan {
        Objects.requireNonNull(sourceImageIds, "sourceImageIds");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(params, "params");
        // 强制不可变拷贝,避免上游误改 record 字段污染提案
        sourceImageIds = List.copyOf(sourceImageIds);
        params = immutableCopy(params);
        // note/conversationId/packageId 允许 null(各自由调用方决定必填与否)
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
