package com.pixflow.module.imagegen.proposal;

import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 规范化后的生图提案(对齐 imagegen.md §5.2 / §十六.11)。
 *
 * <p>记录的是「经过校验 + 规范化」之后的提案事实:
 * <ul>
 *   <li>{@code sourceReferenceKey} 是一个 concrete IMAGE key</li>
 *   <li>{@code prompt} 已 trim</li>
 *   <li>{@code params} 仅含白名单键(白名单外的键在 validator 阶段被拒绝)</li>
 *   <li>{@code note} 用户补充文案,不参与 payloadHash</li>
 *   <li>{@code conversationId} / {@code packageId} 关联会话与素材包</li>
 * </ul>
 *
 * <p>本 record 是 imagegen 模块内的事实数据，序列化进 ephemeral Proposal payload；
 * 未确认内容不写业务数据库。
 */
public record ImagegenPlan(
        String sourceReferenceKey,
        String prompt,
        Map<String, Object> params,
        String note,
        String conversationId,
        long packageId) {

    private static final CanonicalAssetReferenceCodec REFERENCE_CODEC =
            new CanonicalAssetReferenceCodec();

    public ImagegenPlan {
        Objects.requireNonNull(sourceReferenceKey, "sourceReferenceKey");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(params, "params");
        ImageAssetReferenceKey source = requireSourceReference(sourceReferenceKey);
        if (packageId <= 0 || source.packageId() != packageId) {
            throw new IllegalArgumentException("packageId must match sourceReferenceKey");
        }
        // 强制不可变拷贝,避免上游误改 record 字段污染提案
        params = immutableCopy(params);
        // note/conversationId 允许 null，各自由调用边界决定是否必填。
    }

    /** 返回构造阶段已经验证过类型与 package 一致性的源图引用。 */
    public ImageAssetReferenceKey sourceReference() {
        return requireSourceReference(sourceReferenceKey);
    }

    private static ImageAssetReferenceKey requireSourceReference(String referenceKey) {
        AssetReferenceKey parsed = REFERENCE_CODEC.parse(referenceKey);
        if (parsed instanceof ImageAssetReferenceKey imageReference) {
            return imageReference;
        }
        throw new IllegalArgumentException("sourceReferenceKey must be an IMAGE reference");
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
