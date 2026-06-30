package com.pixflow.agent.memory;

/**
 * 记忆召回通道枚举。
 *
 * <p>对应 {@code agent.md §6.2} 的 4 通道：
 * <ul>
 *   <li>PREFERENCE：用户偏好画像（永远触发，注入 instruction_memory section）</li>
 *   <li>SKU_HISTORY：SKU 处理历史（精确，注入 long_term_memory.sku_history 子段）</li>
 *   <li>INSIGHT_VECTOR：分析结论向量（Qdrant，注入 long_term_memory.insights.vector）</li>
 *   <li>INSIGHT_FULLTEXT：分析结论 FULLTEXT（MySQL，注入 long_term_memory.insights.fulltext）</li>
 * </ul>
 */
public enum RecallChannel {
    PREFERENCE,
    SKU_HISTORY,
    INSIGHT_VECTOR,
    INSIGHT_FULLTEXT
}