package com.pixflow.module.memory.insight;

import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.ai.embedding.EmbeddingResult;
import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.recall.InsightFilter;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryRanker;
import com.pixflow.module.memory.recall.RrfFuser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HybridInsightRecallService implements InsightRecallService {
    private final EmbeddingClient embeddingClient;

    private final InsightVectorSearch vectorSearch;

    private final VectorRecallReadiness vectorReadiness;

    private final InsightKeywordSearch keywordSearch;

    private final RrfFuser rrfFuser;

    private final MemoryRanker ranker;

    private final MemoryProperties properties;

    public HybridInsightRecallService(
            EmbeddingClient embeddingClient,
            InsightVectorSearch vectorSearch,
            VectorRecallReadiness vectorReadiness,
            InsightKeywordSearch keywordSearch,
            RrfFuser rrfFuser,
            MemoryRanker ranker,
            MemoryProperties properties) {
        this.embeddingClient = embeddingClient;
        this.vectorSearch = vectorSearch;
        this.vectorReadiness = Objects.requireNonNull(vectorReadiness, "vectorReadiness");
        this.keywordSearch = Objects.requireNonNull(keywordSearch, "keywordSearch");
        this.rrfFuser = Objects.requireNonNull(rrfFuser, "rrfFuser");
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public InsightRecallResult recall(String query, InsightFilter filter, int topN) {
        if (query == null || query.isBlank()) {
            return InsightRecallResult.empty();
        }
        MemoryProperties.Recall recall = properties.getInsight().getRecall();
        List<MemoryItem> vectorItems = List.of();
        List<MemoryItem> keywordItems = List.of();
        Map<String, Object> trace = new LinkedHashMap<>();
        List<String> degradedReasons = new ArrayList<>();

        if (!vectorReadiness.ready()) {
            degradedReasons.add(vectorReadiness.degradedReason());
        } else if (embeddingClient == null || vectorSearch == null) {
            degradedReasons.add("vector_not_configured");
        } else {
            try {
                EmbeddingResult embedding = embeddingClient.embed(List.of(query));
                if (embedding.vectors().isEmpty()) {
                    degradedReasons.add("vector_empty_embedding");
                } else {
                    vectorItems = vectorSearch.search(
                            embedding.vectors().get(0).values(),
                            recall.getTopnEach(),
                            (float) recall.getVectorThreshold(),
                            filter);
                }
            } catch (RuntimeException ex) {
                degradedReasons.add("vector_unavailable");
            }
        }

        try {
            keywordItems = keywordSearch.search(query, filter, recall.getTopnEach());
        } catch (RuntimeException ex) {
            degradedReasons.add("keyword_failed:" + ex.getClass().getSimpleName());
        }

        List<MemoryItem> fused = rrfFuser.fuse(List.of(vectorItems, keywordItems), recall.getRrfK());
        List<MemoryItem> ranked = ranker.rank(fused, Math.min(Math.max(1, topN), recall.getTopn()));
        trace.put("query", query);
        trace.put("vector_candidates", vectorItems.size());
        trace.put("keyword_candidates", keywordItems.size());
        trace.put("fused_candidates", fused.size());
        trace.put("returned", ranked.size());
        trace.put("degraded_reasons", degradedReasons);
        // 降级不阻断主对话；只要一路召回成功，就继续生成可注入 memory section。
        return new InsightRecallResult(ranked, !degradedReasons.isEmpty(), trace);
    }
}
