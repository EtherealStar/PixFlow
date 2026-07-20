package com.pixflow.module.memory.context;

import com.pixflow.module.memory.insight.InsightRecallResult;
import com.pixflow.module.memory.insight.InsightRecallService;
import com.pixflow.module.memory.preference.PreferenceService;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import com.pixflow.module.memory.recall.RecallPlan;
import com.pixflow.module.memory.recall.RecallPlanner;
import com.pixflow.module.memory.recall.RecallSignalExtractor;
import com.pixflow.module.memory.recall.RecallSignals;
import com.pixflow.module.memory.skuhistory.SkuHistoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MemoryContextBuilder {
    public static final String USER_PREFERENCES = "user_preferences";

    public static final String SKU_HISTORY = "sku_history";

    public static final String ANALYSIS_INSIGHTS = "analysis_insights";

    private final RecallSignalExtractor signalExtractor;

    private final RecallPlanner planner;

    private final PreferenceService preferenceService;

    private final SkuHistoryService skuHistoryService;

    private final InsightRecallService insightRecallService;

    public MemoryContextBuilder(
            RecallSignalExtractor signalExtractor,
            RecallPlanner planner,
            PreferenceService preferenceService,
            SkuHistoryService skuHistoryService,
            InsightRecallService insightRecallService) {
        this.signalExtractor = Objects.requireNonNull(signalExtractor, "signalExtractor");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.skuHistoryService = Objects.requireNonNull(skuHistoryService, "skuHistoryService");
        this.insightRecallService = Objects.requireNonNull(insightRecallService, "insightRecallService");
    }

    public MemoryContext build(MemoryContextRequest request) {
        RecallSignals signals = signalExtractor.extract(request);
        RecallPlan plan = planner.plan(request, signals);
        List<MemorySection> sections = new ArrayList<>();
        boolean degraded = false;

        List<MemoryItem> preferences = plan.recallPreference()
                ? preferenceService.recallPreferences(plan.preferenceMaxItems())
                : List.of();
        sections.add(section(USER_PREFERENCES, preferences, "偏好", Map.of("required", plan.recallPreference())));

        List<MemoryItem> skuHistory = plan.recallSkuHistory()
                ? skuHistoryService.recallBySkuIds(plan.skuIds(), plan.skuHistoryMaxItemsPerSku())
                : List.of();
        sections.add(section(SKU_HISTORY, skuHistory, "SKU历史", Map.of("sku_ids", plan.skuIds())));

        InsightRecallResult insightResult = plan.recallInsight()
                ? insightRecallService.recall(plan.insightQuery(), plan.insightFilter(), plan.insightTopN())
                : InsightRecallResult.empty();
        degraded = insightResult.degraded();
        sections.add(section(ANALYSIS_INSIGHTS, insightResult.items(), "分析结论", insightResult.trace()));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("signals", Map.of(
                "sku_ids", signals.skuIds(),
                "categories", signals.categories(),
                "intents", signals.intents(),
                "metric_terms", signals.metricTerms()));
        trace.put("plan", plan.trace());
        trace.put("insight", insightResult.trace());
        trace.put("degraded", degraded);
        return new MemoryContext(request.conversationId(), request.turnNo(), sections, trace, degraded);
    }

    private static MemorySection section(String name, List<MemoryItem> items, String label, Map<String, Object> trace) {
        String rendered = render(label, items);
        return new MemorySection(name, rendered, items, estimateTokens(rendered), trace);
    }

    private static String render(String label, List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(label).append(":\n");
        for (MemoryItem item : items) {
            builder.append("- ").append(renderItem(item)).append('\n');
        }
        return builder.toString().trim();
    }

    private static String renderItem(MemoryItem item) {
        if (item.type() == MemoryType.INSIGHT && !item.source().isBlank()) {
            return item.text() + "（来源：" + item.source() + "）";
        }
        return item.text();
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 2.5d));
    }
}
