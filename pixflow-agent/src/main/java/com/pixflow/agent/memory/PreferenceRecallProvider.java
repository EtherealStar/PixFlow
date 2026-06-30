package com.pixflow.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 通道 0：用户偏好画像召回（永远触发）。
 *
 * <p>对应 {@code agent.md §6.2}：PREFERENCE 通道，
 * 从 user_preference 表全量读，注入 instruction_memory section。
 *
 * <p>本期不直连 MySQL：调用方注入 PreferenceProvider（默认实现无 DB 时返回空）。
 */
@Component
public class PreferenceRecallProvider implements MemoryChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(PreferenceRecallProvider.class);

    @Override
    public RecallChannel channel() {
        return RecallChannel.PREFERENCE;
    }

    @Override
    public Optional<List<MemoryItem>> recall(MemoryRecallSignal signal) {
        // 本期实现：保守返回空（无 DB schema）；下个迭代对接 user_preference 表
        log.debug("PreferenceRecallProvider: returning empty (DB not wired in this iteration)");
        return Optional.of(List.of());
    }
}