package com.pixflow.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 通道 2 & 3：分析结论（向量 + 全文）双路召回。
 *
 * <p>对应 {@code agent.md §6.2}：INSIGHT_VECTOR + INSIGHT_FULLTEXT。
 *
 * <p>本期实现：保守返回空（Qdrant 索引 + analysis_insight 表需在后续迭代接入）。
 * 类目 filter 留接口：本期 categoryFilterEnabled = false（无 category 字段）。
 */
@Component
public class InsightRecallProvider implements MemoryChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(InsightRecallProvider.class);

    @Override
    public RecallChannel channel() {
        return RecallChannel.INSIGHT_VECTOR;
    }

    @Override
    public Optional<List<MemoryItem>> recall(MemoryRecallSignal signal) {
        log.debug("InsightRecallProvider: signal received, query='{}'",
                signal.userMessage() == null ? "" : signal.userMessage().substring(0, Math.min(50, signal.userMessage().length())));
        // 本期实现：保守返回空；下个迭代对接 Qdrant + analysis_insight 表
        return Optional.of(List.of());
    }
}