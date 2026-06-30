package com.pixflow.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 单个记忆召回通道的 provider SPI。
 *
 * <p>由 {@link MemoryRecallPlanner} 调用；本期实现由本模块或下游模块提供。
 *
 * <p>降级约定：provider 内部失败时返回 {@link Optional#empty()}，
 * planner 记 WARN 并继续其它通道。
 */
public interface MemoryChannelProvider {

    /**
     * 通道名。
     */
    RecallChannel channel();

    /**
     * 召回结果。
     *
     * @return 有结果返回 items，无结果或失败返回空 Optional
     */
    Optional<List<MemoryItem>> recall(MemoryRecallSignal signal);
}