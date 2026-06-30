package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.stereotype.Component;

/**
 * 动态 section 7：会话记忆（session_memory）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 7 行。
 *
 * <p>fingerprint = "{lastSummarizedSeq}.{contentHash}"。
 * 内容来自 {@code ctx.sessionMemory().markdown()}；空 content → 跳过该段。
 */
@Component
public final class SessionMemorySection implements SectionRenderer {

    @Override
    public String key() {
        return "session_memory";
    }

    @Override
    public String title() {
        return "会话记忆";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        if (ctx.sessionMemory() == null || ctx.sessionMemory().isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        String markdown = ctx.sessionMemory().markdown();
        String fingerprint = "session." + ctx.sessionMemory().contentHash();
        return new PromptSection(key(), title(), markdown.strip(), fingerprint, true);
    }
}