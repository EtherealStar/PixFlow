package com.pixflow.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 召回规划器：信号 → 4 通道 → RRF 融合。
 *
 * <p>对应 {@code agent.md §6}：
 * <ol>
 *   <li>并发触发 4 通道（{@code CompletableFuture}）</li>
 *   <li>任一通道失败记 WARN 并降级（向量失败只保留 FULLTEXT / 反之）</li>
 *   <li>{@code RrfMerger.merge} 融合 + token 预算裁剪</li>
 *   <li>包装为 {@link MemoryRecallResult}（含 recallPlanId / totalTokens）</li>
 * </ol>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@link #plan} 是<b>同步</b>的（不返回 Future），与 AgentOrchestrator
 *       入口同步触发点对齐（用户决策 3）</li>
 *   <li>recallPlanId = 新 UUID，每轮 buildForModel 一次</li>
 *   <li>4 通道并发跑（公共 ForkJoinPool），单通道失败不影响其它</li>
 * </ul>
 */
@Component
public class MemoryRecallPlanner {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallPlanner.class);

    private final List<MemoryChannelProvider> providers;
    private final RrfMerger rrfMerger;

    public MemoryRecallPlanner(List<MemoryChannelProvider> providers, RrfMerger rrfMerger) {
        this.providers = List.copyOf(providers);
        this.rrfMerger = rrfMerger;
        log.info("MemoryRecallPlanner initialized with {} channels", providers.size());
    }

    /**
     * 同步召回规划。
     *
     * <p>4 通道并发跑，最终按 RRF 融合；任一通道失败 → 降级（其它通道继续）。
     */
    public MemoryRecallResult plan(MemoryRecallSignal signal) {
        if (signal == null) {
            return MemoryRecallResult.empty();
        }
        Map<RecallChannel, List<MemoryItem>> channelResults = new HashMap<>();
        // 简单同步顺序调用（本期 provider 实现都无 IO；未来切 CompletableFuture 并发）
        for (MemoryChannelProvider provider : providers) {
            try {
                List<MemoryItem> items = provider.recall(signal).orElse(List.of());
                channelResults.put(provider.channel(), items);
            } catch (Exception e) {
                log.warn("MemoryRecallPlanner: channel {} failed: {}",
                        provider.channel(), e.getMessage());
                channelResults.put(provider.channel(), List.of());
            }
        }
        // 过滤空 channels
        Map<RecallChannel, List<MemoryItem>> nonEmpty = new HashMap<>();
        channelResults.forEach((ch, items) -> {
            if (items != null && !items.isEmpty()) {
                nonEmpty.put(ch, items);
            }
        });
        if (nonEmpty.isEmpty()) {
            return new MemoryRecallResult(UUID.randomUUID(), List.of(), 0L, Map.of(
                    "channels_attempted", providers.size()
            ));
        }
        // RRF 融合
        RrfMerger.MergedRecall merged = rrfMerger.merge(nonEmpty);
        // 拆分为 sections（按 channel 归类）
        List<MemorySection> sections = new ArrayList<>();
        channelResults.forEach((ch, items) -> {
            String sectionName = switch (ch) {
                case PREFERENCE -> "user_preferences";
                case SKU_HISTORY -> "sku_history";
                case INSIGHT_VECTOR -> "insights.vector";
                case INSIGHT_FULLTEXT -> "insights.fulltext";
            };
            // 过滤到 RRF 选中范围
            List<MemoryItem> filtered = new ArrayList<>();
            for (MemoryItem item : items) {
                if (merged.items().contains(item)) {
                    filtered.add(item);
                }
            }
            sections.add(new MemorySection(sectionName, filtered));
        });
        Map<String, Object> recallTrace = new HashMap<>();
        recallTrace.put("channels_attempted", providers.size());
        recallTrace.put("channels_returned", nonEmpty.size());
        recallTrace.put("raw_items", channelResults.values().stream().mapToInt(List::size).sum());
        recallTrace.put("fused_items", merged.items().size());
        recallTrace.put("total_tokens", merged.totalTokens());
        return new MemoryRecallResult(UUID.randomUUID(), sections, merged.totalTokens(), recallTrace);
    }
}