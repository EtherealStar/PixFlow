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
import com.pixflow.module.memory.recall.RecallReferenceResolver;
import com.pixflow.module.memory.recall.ResolvedRecallReferences;
import com.pixflow.module.memory.skuhistory.SkuHistoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryContextBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryContextBuilder.class);

    public static final String USER_PREFERENCES = "user_preferences";

    public static final String SKU_HISTORY = "sku_history";

    public static final String ANALYSIS_INSIGHTS = "analysis_insights";

    private final RecallSignalExtractor signalExtractor;

    private final RecallPlanner planner;

    private final RecallReferenceResolver referenceResolver;

    private final PreferenceService preferenceService;

    private final SkuHistoryService skuHistoryService;

    private final InsightRecallService insightRecallService;

    private final Clock clock;

    public MemoryContextBuilder(
            RecallSignalExtractor signalExtractor,
            RecallPlanner planner,
            RecallReferenceResolver referenceResolver,
            PreferenceService preferenceService,
            SkuHistoryService skuHistoryService,
            InsightRecallService insightRecallService,
            Clock clock) {
        this.signalExtractor = Objects.requireNonNull(signalExtractor, "signalExtractor");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver");
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.skuHistoryService = Objects.requireNonNull(skuHistoryService, "skuHistoryService");
        this.insightRecallService = Objects.requireNonNull(insightRecallService, "insightRecallService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MemoryContext build(MemoryContextRequest request) {
        ResolvedRecallReferences resolvedReferences = referenceResolver.resolve(request.references());
        RecallSignals extractedSignals = signalExtractor.extract(request);
        RecallSignals signals = new RecallSignals(resolvedReferences.skuIds(),
                merge(extractedSignals.categories(), resolvedReferences.categoryHints()),
                extractedSignals.intents(), extractedSignals.metricTerms());
        RecallPlan plan = planner.plan(request, signals);
        Instant asOf = clock.instant();
        List<MemorySection> sections = new ArrayList<>();
        boolean degraded = resolvedReferences.trace().stream()
                .anyMatch(item -> !"resolved".equals(item.get("status")));

        List<MemoryItem> preferences = List.of();
        Map<String, Object> preferenceTrace = new LinkedHashMap<>();
        try {
            preferences = plan.recallPreference()
                    ? preferenceService.recallPreferences(plan.preferenceMaxItems())
                    : List.of();
            preferenceTrace.put("dependency_status", "available");
        } catch (RuntimeException failure) {
            LOGGER.warn("Memory preference recall degraded: type={}", failure.getClass().getSimpleName());
            preferenceTrace.put("dependency_status", "unavailable");
            preferenceTrace.put("degraded_reasons", List.of("preference_unavailable"));
            degraded = true;
        }
        sections.add(section(USER_PREFERENCES, preferences, "偏好", preferenceTrace));

        List<MemoryItem> skuHistory = List.of();
        Map<String, Object> skuTrace = new LinkedHashMap<>();
        skuTrace.put("sku_ids", plan.skuIds());
        try {
            skuHistory = plan.recallSkuHistory()
                    ? skuHistoryService.recallBySkuIds(plan.skuIds(), plan.skuHistoryMaxItemsPerSku()) : List.of();
            skuTrace.put("dependency_status", "available");
        } catch (RuntimeException failure) {
            LOGGER.warn("Memory SKU history recall degraded: type={}", failure.getClass().getSimpleName());
            skuTrace.put("dependency_status", "unavailable");
            skuTrace.put("degraded_reasons", List.of("sku_history_unavailable"));
            degraded = true;
        }
        sections.add(section(SKU_HISTORY, skuHistory, "SKU历史", skuTrace));

        InsightRecallResult insightResult;
        try {
            insightResult = plan.recallInsight()
                    ? insightRecallService.recall(
                            plan.insightQuery(), plan.insightFilter(), plan.insightTopN(), asOf)
                    : InsightRecallResult.empty();
        } catch (RuntimeException failure) {
            LOGGER.warn("Memory insight recall degraded: type={}", failure.getClass().getSimpleName());
            insightResult = new InsightRecallResult(
                    List.of(),
                    true,
                    Map.of(
                            "dependency_status", "unavailable",
                            "degraded_reasons", List.of("insight_unavailable")));
            degraded = true;
        }
        degraded = degraded || insightResult.degraded();
        sections.add(section(ANALYSIS_INSIGHTS, insightResult.items(), "分析结论", insightResult.trace()));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("signals", Map.of(
                "sku_ids", signals.skuIds(),
                "categories", signals.categories(),
                "intents", signals.intents(),
                "metric_terms", signals.metricTerms()));
        trace.put("plan", plan.trace());
        trace.put("reference_resolution", resolvedReferences.trace());
        trace.put("insight", insightResult.trace());
        trace.put("degraded", degraded);
        List<MemorySection> budgeted = applyBudget(sections, request.tokenBudget());
        trace.put("token_budget", request.tokenBudget());
        trace.put("as_of", asOf.toString());
        trace.put("used_tokens", budgeted.stream().mapToInt(MemorySection::tokenEstimate).sum());
        return new MemoryContext(request.conversationId(), request.turnNo(), budgeted, trace, degraded);
    }

    private static List<String> merge(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).distinct().toList();
    }

    private static List<MemorySection> applyBudget(List<MemorySection> sections, int budget) {
        int remaining = budget;
        List<MemorySection> selected = new ArrayList<>();
        for (MemorySection section : sections) {
            List<MemoryItem> items = new ArrayList<>();
            for (MemoryItem item : section.items()) {
                List<MemoryItem> candidateItems = new ArrayList<>(items);
                candidateItems.add(item);
                MemorySection candidate = section(section.name(), candidateItems, labelFor(section.name()), Map.of());
                if (candidate.tokenEstimate() > remaining) {
                    break;
                }
                items = candidateItems;
            }
            Map<String, Object> trace = new LinkedHashMap<>(section.trace());
            trace.put("requested_item_count", section.items().size());
            trace.put("selected_item_count", items.size());
            trace.put("omission_reason", items.size() == section.items().size() ? "" : "token_budget");
            MemorySection budgeted = section(section.name(), items, labelFor(section.name()), trace);
            remaining -= budgeted.tokenEstimate();
            selected.add(budgeted);
        }
        return selected;
    }

    private static String labelFor(String name) {
        return switch (name) {
            case USER_PREFERENCES -> "偏好";
            case SKU_HISTORY -> "SKU历史";
            case ANALYSIS_INSIGHTS -> "分析结论";
            default -> name;
        };
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
