package com.pixflow.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 通道 1：SKU 处理历史召回（精确）。
 *
 * <p>对应 {@code agent.md §6.2}：SKU_HISTORY 通道。
 * 触发条件：signal.currentPackageSkuIds 非空 OR mentionedSkuIds 非空。
 */
@Component
public class SkuHistoryRecallProvider implements MemoryChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SkuHistoryRecallProvider.class);

    @Override
    public RecallChannel channel() {
        return RecallChannel.SKU_HISTORY;
    }

    @Override
    public Optional<List<MemoryItem>> recall(MemoryRecallSignal signal) {
        if (signal.currentPackageSkuIds().isEmpty() && signal.mentionedSkuIds().isEmpty()) {
            return Optional.of(List.of());
        }
        log.debug("SkuHistoryRecallProvider: signal received with {} package skus, {} mentioned skus",
                signal.currentPackageSkuIds().size(), signal.mentionedSkuIds().size());
        // 本期实现：保守返回空；下个迭代对接 sku_history 表
        return Optional.of(List.of());
    }
}