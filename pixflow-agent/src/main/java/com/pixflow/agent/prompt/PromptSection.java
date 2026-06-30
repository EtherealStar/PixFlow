package com.pixflow.agent.prompt;

import java.util.Objects;

/**
 * system prompt 的一段不可变渲染单元。
 *
 * <p>对应 {@code agent.md §4.2} 的 record 定义：
 * 6 字段（key / title / body / fingerprint / cacheable / 渲染输出）。
 * 装配期按 fingerprint 命中缓存，未命中调 {@link #render()} 后写回。
 *
 * <p>设计要点：
 * <ul>
 *   <li>body 可空（空 body 触发 section 跳过渲染）</li>
 *   <li>cacheable = false 时不参与 section cache（与 prompt-architecture.md 一致）</li>
 *   <li>fingerprint 计算由 section 实现类各自负责（外部零认知）</li>
 * </ul>
 */
public record PromptSection(
        String key,
        String title,
        String body,
        String fingerprint,
        boolean cacheable
) {

    public PromptSection {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /**
     * 渲染为完整 Markdown 片段：`# {title}\n{body}`。
     *
     * <p>对应 prompt-architecture.md 中 `prompts/sections.py` 的 `render()` 行为。
     * 注意：title 段不含前缀（无 `##`），section 间的层级由 assembler
     * 拼接的 `\n\n` 体现。
     */
    public String render() {
        if (body.isEmpty()) {
            return "";
        }
        return "# " + title + "\n" + body;
    }
}
