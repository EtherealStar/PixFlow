package com.pixflow.agent.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 召回产物：RRF 融合后的所有 MemorySection + 召回计划 ID + 实际累计 token。
 *
 * <p>对应 {@code agent.md §6.6} 中的 MemoryRecallResult。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>recallPlanId 是每轮 buildForModel 前新建的 UUID</li>
 *   <li>totalTokens 是按 RRF 顺序累加的 jtokkit 估算值（已截断）</li>
 *   <li>section 之间相互独立（无重叠）</li>
 * </ul>
 */
public record MemoryRecallResult(
        UUID recallPlanId,
        List<MemorySection> sections,
        long totalTokens,
        Map<String, Object> recallTrace
) {

    public MemoryRecallResult {
        Objects.requireNonNull(recallPlanId, "recallPlanId");
        sections = sections == null ? List.of() : List.copyOf(sections);
        recallTrace = recallTrace == null ? Map.of() : Map.copyOf(recallTrace);
    }

    /**
     * 构造空结果（无召回）。
     */
    public static MemoryRecallResult empty() {
        return new MemoryRecallResult(UUID.randomUUID(), List.of(), 0L, Map.of());
    }

    /**
     * 按 sectionName 取子段。
     */
    public MemorySection section(String sectionName) {
        return sections.stream()
                .filter(s -> s.sectionName().equals(sectionName))
                .findFirst()
                .orElseGet(() -> new MemorySection(sectionName, List.of()));
    }
}