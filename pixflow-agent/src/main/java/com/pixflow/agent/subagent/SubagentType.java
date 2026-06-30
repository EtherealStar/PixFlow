package com.pixflow.agent.subagent;

/**
 * Subagent 类型枚举。
 *
 * <p>对应 {@code agent.md §8.1} 三处复用（4 type）：
 * <ul>
 *   <li>VISION：{@code agent(type=vision)} 工具</li>
 *   <li>EXPLORE：{@code agent(type=explore)} 工具</li>
 *   <li>SUMMARIZATION：destructive compaction 摘要（ForkChildSummarizationPort）</li>
 *   <li>SESSION_MEMORY_EXTRACTION：Session Memory 累积提取</li>
 * </ul>
 */
public enum SubagentType {
    VISION,
    EXPLORE,
    SUMMARIZATION,
    SESSION_MEMORY_EXTRACTION
}