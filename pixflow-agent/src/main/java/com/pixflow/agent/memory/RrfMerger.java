package com.pixflow.agent.memory;

import com.pixflow.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多通道 RRF 融合 + token 预算裁剪。
 *
 * <p>对应 {@code agent.md §6.3}：
 * 标准 Reciprocal Rank Fusion 算法（k=60 可配）+ jtokkit token 估算 + 累加截断。
 *
 * <p>算法：
 * <ol>
 *   <li>各通道已排序（channel 内部 rank 从 0 开始）</li>
 *   <li>RRF 分数 = Σ (1 / (k + rank_i + 1))，跨通道 itemId 相同时累加</li>
 *   <li>按 RRF 分数降序排</li>
 *   <li>maxItems 上限（默认 50）</li>
 *   <li>按排序顺序累加 token 数；超过 maxTokens（默认 4000）截断</li>
 * </ol>
 *
 * <p>降级矩阵：单通道为空 → 跳过该通道（不影响其他通道融合）。
 */
@Component
public class RrfMerger {

    private static final Logger log = LoggerFactory.getLogger(RrfMerger.class);

    private final AgentProperties props;
    private final TokenEstimator tokenEstimator;

    public RrfMerger(AgentProperties props, TokenEstimator tokenEstimator) {
        this.props = props;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 多通道融合。
     *
     * @param channels 每个 RecallChannel 对应的有序 MemoryItem 列表（按 channel 分数降序）
     * @return RRF 融合 + token 截断后的有序结果
     */
    public MergedRecall merge(Map<RecallChannel, List<MemoryItem>> channels) {
        Objects.requireNonNull(channels, "channels");
        int k = props.getMemory().getRecall().getRrfK();
        int maxItems = props.getMemory().getRecall().getMaxItems();
        int maxTokens = props.getMemory().getRecall().getMaxTokens();

        // RRF 累加
        Map<String, ScoredItem> scoredMap = new HashMap<>();
        for (Map.Entry<RecallChannel, List<MemoryItem>> entry : channels.entrySet()) {
            List<MemoryItem> items = entry.getValue();
            if (items == null || items.isEmpty()) continue;
            for (int rank = 0; rank < items.size(); rank++) {
                MemoryItem item = items.get(rank);
                double rrfScore = 1.0 / (k + rank + 1);
                ScoredItem existing = scoredMap.get(item.itemId());
                if (existing == null) {
                    scoredMap.put(item.itemId(), new ScoredItem(item, rrfScore));
                } else {
                    existing.addScore(rrfScore);
                }
            }
        }
        // 排序 + 限 maxItems
        List<ScoredItem> sorted = new ArrayList<>(scoredMap.values());
        sorted.sort(Comparator.comparingDouble((ScoredItem s) -> s.score).reversed());
        if (sorted.size() > maxItems) {
            sorted = sorted.subList(0, maxItems);
        }
        // token 截断
        long totalTokens = 0;
        List<MemoryItem> truncated = new ArrayList<>();
        for (ScoredItem si : sorted) {
            int tokens = tokenEstimator.estimate(si.item);
            if (totalTokens + tokens > maxTokens) {
                log.debug("RrfMerger: token budget {} exceeded, dropping item {} (would add {} tokens)",
                        maxTokens, si.item.itemId(), tokens);
                break;
            }
            truncated.add(si.item);
            totalTokens += tokens;
        }
        return new MergedRecall(truncated, totalTokens);
    }

    /**
     * 融合结果：有序 items + 实际累计 token。
     */
    public record MergedRecall(List<MemoryItem> items, long totalTokens) {}

    private static final class ScoredItem {
        final MemoryItem item;
        double score;

        ScoredItem(MemoryItem item, double initial) {
            this.item = item;
            this.score = initial;
        }

        void addScore(double delta) {
            this.score += delta;
        }
    }
}