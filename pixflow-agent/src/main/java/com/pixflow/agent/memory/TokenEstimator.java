package com.pixflow.agent.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 估算工具（jtokkit）。
 *
 * <p>对应 {@code agent.md §6.3} 的 token 预算裁剪：jtokkit 估算每条 MemoryItem 的
 * token 数，按 RRF 顺序累加直至不超过 maxTokens 截断。
 *
 * <p>本期使用 cl100k_base 编码（与 OpenAI GPT-3.5/4 同款，与国产模型字节级差异
 * ≤ 10%）。
 */
@Component
public class TokenEstimator {

    private final Encoding encoding;

    public TokenEstimator() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);
    }

    /**
     * 估算单条字符串的 token 数。
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }

    /**
     * 估算单条 MemoryItem 的 token 数（基于 preview）。
     */
    public int estimate(MemoryItem item) {
        return estimate(item.preview());
    }

    /**
     * 估算整段文本的 token 数。
     */
    public int estimateAll(List<MemoryItem> items) {
        return items.stream().mapToInt(this::estimate).sum();
    }
}