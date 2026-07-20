package com.pixflow.module.memory.recall;

import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.context.MemoryContextRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class RecallPlanner {
    private final MemoryProperties properties;

    public RecallPlanner(MemoryProperties properties) {
        this.properties = properties;
    }

    public RecallPlan plan(MemoryContextRequest request, RecallSignals signals) {
        int insightTopN = properties.getPrompt().getInsightTopn();
        if (request.tokenBudget() != null && request.tokenBudget() < properties.getPrompt().getMaxTokens() / 2) {
            insightTopN = Math.max(1, insightTopN / 2);
        }

        boolean recallSkuHistory = !signals.skuIds().isEmpty();
        boolean recallInsight = signals.hasInsightSignal();
        String insightQuery = recallInsight ? buildInsightQuery(request, signals) : "";
        InsightFilter filter = new InsightFilter(signals.skuIds(), signals.categories(), 0, Map.of());

        Map<String, Object> trace = new LinkedHashMap<>();
        // 偏好画像是稳定用户上下文，每轮都由系统召回，不交给模型自行判断。
        trace.put("preference_required", true);
        trace.put("sku_signal_count", signals.skuIds().size());
        trace.put("insight_signal_count", signals.categories().size()
                + signals.intents().size() + signals.metricTerms().size());
        trace.put("insight_topn", insightTopN);

        return new RecallPlan(
                true,
                signals.skuIds(),
                recallSkuHistory,
                recallInsight,
                insightQuery,
                filter,
                properties.getPrompt().getPreferenceMaxItems(),
                properties.getPrompt().getSkuHistoryMaxItemsPerSku(),
                insightTopN,
                trace);
    }

    private static String buildInsightQuery(MemoryContextRequest request, RecallSignals signals) {
        StringJoiner joiner = new StringJoiner(" ");
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            joiner.add(request.userPrompt().trim());
        }
        signals.categories().forEach(joiner::add);
        signals.intents().forEach(joiner::add);
        signals.metricTerms().forEach(joiner::add);
        return joiner.toString();
    }
}
