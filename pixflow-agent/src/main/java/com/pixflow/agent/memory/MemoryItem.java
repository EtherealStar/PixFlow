package com.pixflow.agent.memory;

import java.util.Map;
import java.util.Objects;

/**
 * 单条记忆召回项。
 *
 * <p>对应 {@code agent.md §6} 的最小数据单元——
 * 来自某个 RecallChannel 的召回结果（去业务化包装）。
 *
 * @param itemId    业务 ID（如 SKU ID / analysis_insight ID）
 * @param channel   召回通道
 * @param score     原始排序分（channel 内部用）
 * @param preview   渲染到 prompt 的文本（≤ 1KB）
 * @param metadata  给 trace 用的元信息（来源表 / confidence 等）
 */
public record MemoryItem(
        String itemId,
        RecallChannel channel,
        double score,
        String preview,
        Map<String, Object> metadata
) {

    public MemoryItem {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(preview, "preview");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 构造最小 MemoryItem（无 metadata）。
     */
    public static MemoryItem of(String itemId, RecallChannel channel, double score, String preview) {
        return new MemoryItem(itemId, channel, score, preview, Map.of());
    }
}