package com.pixflow.harness.context.sessionmemory;

import java.util.Objects;

/**
 * 会话记忆内容载体。
 *
 * <p>对应 {@code agent.md §7.2.1} 的 Markdown 累积视图：单会话一行的压缩表达。
 * 实际持久化由 agent 模块的 SessionMemoryService 承担，本类型仅作为 SPI
 * 边界上的不可变数据载体。
 *
 * @param markdown   累积的 Markdown 文本（任务状态/已确认决策/用户偏好更新/待澄清）
 * @param contentHash 内容 MD5 哈希，参与 fingerprint 计算
 */
public record SessionMemoryContent(String markdown, String contentHash) {

    public SessionMemoryContent {
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(contentHash, "contentHash");
    }

    /**
     * 是否为空内容（用于 section 渲染时过滤）。
     */
    public boolean isEmpty() {
        return markdown.isBlank();
    }
}
