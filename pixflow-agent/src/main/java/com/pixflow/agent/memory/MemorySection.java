package com.pixflow.agent.memory;

import java.util.List;
import java.util.Objects;

/**
 * 召回结果中的一段视图。
 *
 * <p>对应 {@code agent.md §6.6} 中 long_term_memory section 的子段划分——
 * 每个 MemorySection 是一个命名子段（含若干 MemoryItem）。
 *
 * <p>section 名称约定：
 * <ul>
 *   <li>{@code "user_preferences"}：用户偏好画像（注入 instruction_memory section）</li>
 *   <li>{@code "sku_history"}：SKU 处理历史</li>
 *   <li>{@code "insights.vector"} / {@code "insights.fulltext"}：分析结论</li>
 * </ul>
 *
 * @param sectionName  子段名
 * @param items        召回项列表（已按 channel 排序）
 */
public record MemorySection(String sectionName, List<MemoryItem> items) {

    public MemorySection {
        Objects.requireNonNull(sectionName, "sectionName");
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 是否为空（用于 section 渲染时过滤）。
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}