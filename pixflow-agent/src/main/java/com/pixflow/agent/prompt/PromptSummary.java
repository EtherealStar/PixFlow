package com.pixflow.agent.prompt;

import java.util.List;

/**
 * system prompt 的只读摘要视图（给 eval / trace 用）。
 *
 * <p>对应 {@code agent.md §15.2}：
 * "PromptSummary（每轮 systemPrompt 摘要，不暴露完整 prompt）"。
 *
 * <p>不暴露完整 prompt 内容——只暴露 section keys + fingerprint + 长度，
 * 避免 prompt 主体进入 trace / 日志（可能含商业敏感数据）。
 */
public record PromptSummary(List<SectionDigest> sectionDigests, long totalChars) {

    /**
     * 单段摘要。
     */
    public record SectionDigest(String key, String fingerprint, int bodyChars) {}
}