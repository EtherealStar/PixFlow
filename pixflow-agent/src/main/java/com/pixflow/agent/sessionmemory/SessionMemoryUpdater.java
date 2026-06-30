package com.pixflow.agent.sessionmemory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Session Memory 规则式 fallback（断路器触发后切换）。
 *
 * <p>对应 {@code agent.md §7.5}：
 * <ul>
 *   <li>不调 LLM，纯规则拼接</li>
 *   <li>含最近 1 条 user message + 最后 1 条 assistant message 前 500 字</li>
 *   <li>保证 session memory 永远能累积（即使 LLM 不可用）</li>
 * </ul>
 */
@Component
public class SessionMemoryUpdater {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryUpdater.class);
    private static final int MAX_ASSISTANT_PREVIEW_CHARS = 500;

    /**
     * 拼接 fallback content。
     *
     * @param previousContent 当前 session memory content
     * @param lastUserMessage 最近 1 条 user message（可能为 null）
     * @param lastAssistantMessage 最后 1 条 assistant message（可能为 null）
     * @return 拼接后的新 content
     */
    public String extractFallback(String previousContent,
                                   String lastUserMessage,
                                   String lastAssistantMessage) {
        StringBuilder sb = new StringBuilder();
        if (previousContent != null && !previousContent.isBlank()) {
            sb.append(previousContent).append("\n\n");
        }
        sb.append("## (Fallback) 最近对话摘要\n");
        if (lastUserMessage != null && !lastUserMessage.isBlank()) {
            sb.append("- User: ").append(lastUserMessage).append('\n');
        }
        if (lastAssistantMessage != null && !lastAssistantMessage.isBlank()) {
            String preview = lastAssistantMessage.length() > MAX_ASSISTANT_PREVIEW_CHARS
                    ? lastAssistantMessage.substring(0, MAX_ASSISTANT_PREVIEW_CHARS) + "..."
                    : lastAssistantMessage;
            sb.append("- Assistant: ").append(preview).append('\n');
        }
        log.debug("SessionMemoryUpdater: fallback content size = {} chars", sb.length());
        return sb.toString();
    }
}