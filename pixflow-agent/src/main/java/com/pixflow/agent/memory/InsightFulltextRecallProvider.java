package com.pixflow.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 通道 3：分析结论全文检索（MySQL FULLTEXT）。
 *
 * <p>对应 {@code agent.md §6.2}：INSIGHT_FULLTEXT 通道。
 * 触发条件：永远（用 userMessage 作 query）。
 */
@Component
public class InsightFulltextRecallProvider implements MemoryChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(InsightFulltextRecallProvider.class);

    @Override
    public RecallChannel channel() {
        return RecallChannel.INSIGHT_FULLTEXT;
    }

    @Override
    public Optional<List<MemoryItem>> recall(MemoryRecallSignal signal) {
        log.debug("InsightFulltextRecallProvider: query='{}'",
                signal.userMessage() == null ? "" : signal.userMessage().substring(0, Math.min(50, signal.userMessage().length())));
        // 本期实现：保守返回空；下个迭代对接 MySQL FULLTEXT 索引
        return Optional.of(List.of());
    }
}