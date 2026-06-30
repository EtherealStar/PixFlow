package com.pixflow.agent.sessionmemory;

/**
 * Session Memory 提取来源。
 *
 * <p>对应 session_memory 表 source 字段：
 * <ul>
 *   <li>EXTRACTION：fork child LLM 提取</li>
 *   <li>FALLBACK_RULE：连续失败 ≥ 3 次后切换的规则式 fallback</li>
 * </ul>
 */
public enum SessionMemorySource {
    EXTRACTION,
    FALLBACK_RULE
}