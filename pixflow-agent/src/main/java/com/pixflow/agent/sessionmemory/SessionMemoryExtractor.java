package com.pixflow.agent.sessionmemory;

import com.pixflow.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Session Memory fork child LLM 提取。
 *
 * <p>对应 {@code agent.md §7.4.3}：构造 child prompt（按 Markdown 结构模板），
 * 调 SubagentRunner.runAsync 跑 child LLM 提取；child 工具集为空。
 *
 * <p>本期实现：保守返回原 content（等 SubagentRunner 落地后再接）。
 */
@Component
public class SessionMemoryExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMemoryExtractor.class);

    private final AgentProperties props;

    public SessionMemoryExtractor(AgentProperties props) {
        this.props = props;
    }

    /**
     * fork child LLM 提取。
     *
     * @param previousContent 当前 session memory content（可能为空）
     * @param newMessagesJson 增量 messages（seq > lastSummarizedSeq 部分）
     * @return 新 content（替换式）；失败返回 Optional.empty() 触发 fallback
     */
    public Optional<String> extract(String previousContent, String newMessagesJson) {
        if (newMessagesJson == null || newMessagesJson.isBlank()) {
            LOGGER.debug("SessionMemoryExtractor: no new messages, returning previous content");
            return Optional.ofNullable(previousContent);
        }
        // 本期实现：保守返回拼接（SubagentRunner 落地后接 fork child）
        String merged = (previousContent == null ? "" : previousContent)
                + "\n\n## 本回合增量对话\n"
                + newMessagesJson;
        // 截断到 maxContentBytes（防 LLM 提取爆量）
        int maxBytes = props.getSessionMemory().getMaxContentBytes();
        if (merged.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            merged = truncateUtf8(merged, maxBytes);
            LOGGER.warn("SessionMemoryExtractor: content truncated to {} UTF-8 bytes", maxBytes);
        }
        LOGGER.debug("SessionMemoryExtractor: merged content size = {} chars, ~{} tokens",
                merged.length(), estimateTokens(merged));
        return Optional.of(merged);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 2.5d));
    }

    private static String truncateUtf8(String text, int maxBytes) {
        if (text == null || maxBytes <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String chunk = new String(Character.toChars(codePoint));
            int bytes = chunk.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                break;
            }
            out.append(chunk);
            used += bytes;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
