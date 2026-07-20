package com.pixflow.module.memory.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.insight.InsightRecallResult;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import com.pixflow.module.memory.recall.RecallPlanner;
import com.pixflow.module.memory.recall.RecallSignalExtractor;
import com.pixflow.module.memory.recall.ResolvedRecallReferences;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemoryContextBuilderTest {

    @Test
    void usesOneAsOfAndReturnsRecognizableEmptySectionsWhenBudgetIsZero() {
        Instant asOf = Instant.parse("2026-07-20T12:00:00Z");
        AtomicReference<Instant> insightAsOf = new AtomicReference<>();
        MemoryContextBuilder builder = builder(
                Clock.fixed(asOf, ZoneOffset.UTC),
                List.of(item("pref", MemoryType.PREFERENCE, "偏好")),
                List.of(item("sku", MemoryType.SKU_HISTORY, "历史")),
                (query, filter, topN, requestedAsOf) -> {
                    insightAsOf.set(requestedAsOf);
                    return new InsightRecallResult(
                            List.of(item("insight", MemoryType.INSIGHT, "结论")), false, Map.of());
                });

        MemoryContext context = builder.build(request(0));

        assertThat(insightAsOf).hasValue(asOf);
        assertThat(context.recallTrace()).containsEntry("as_of", asOf.toString());
        assertThat(context.sections()).extracting(MemorySection::name).containsExactly(
                MemoryContextBuilder.USER_PREFERENCES,
                MemoryContextBuilder.SKU_HISTORY,
                MemoryContextBuilder.ANALYSIS_INSIGHTS);
        assertThat(context.sections()).allMatch(section -> section.items().isEmpty()
                && section.renderedText().isEmpty() && section.tokenEstimate() == 0);
    }

    @Test
    void omitsAnOversizedFirstItemAndKeepsUnicodeItemsWhole() {
        MemoryItem oversized = item("long", MemoryType.PREFERENCE, "超".repeat(200));
        MemoryItem unicode = item("unicode", MemoryType.PREFERENCE, "白底图适合商品展示");
        MemoryContextBuilder builder = builder(
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
                List.of(oversized, unicode),
                List.of(),
                (query, filter, topN, asOf) -> InsightRecallResult.empty());

        MemoryContext context = builder.build(request(20));

        MemorySection section = context.section(MemoryContextBuilder.USER_PREFERENCES);
        assertThat(section.items()).isEmpty();
        assertThat(section.trace()).containsEntry("omission_reason", "token_budget");
        assertThat(context.sections().stream().mapToInt(MemorySection::tokenEstimate).sum())
                .isLessThanOrEqualTo(20);
    }

    @Test
    void keepsInsightWhenPreferenceAndSkuHistorySourcesFail() {
        MemoryContextBuilder builder = builder(
                maxItems -> {
                    throw new IllegalStateException("preference database unavailable");
                },
                (skuIds, maxItems) -> {
                    throw new IllegalStateException("sku history database unavailable");
                },
                (query, filter, topN, asOf) -> new InsightRecallResult(
                        List.of(item("insight", MemoryType.INSIGHT, "保留的结论")),
                        false,
                        Map.of("dependency_status", "available")));

        MemoryContext context = builder.build(request(200));

        assertThat(context.degraded()).isTrue();
        assertThat(context.section(MemoryContextBuilder.USER_PREFERENCES).trace())
                .containsEntry("dependency_status", "unavailable")
                .containsEntry("degraded_reasons", List.of("preference_unavailable"));
        assertThat(context.section(MemoryContextBuilder.SKU_HISTORY).trace())
                .containsEntry("dependency_status", "unavailable")
                .containsEntry("degraded_reasons", List.of("sku_history_unavailable"));
        assertThat(context.section(MemoryContextBuilder.ANALYSIS_INSIGHTS).items())
                .extracting(MemoryItem::id)
                .containsExactly("insight");
    }

    @Test
    void returnsBoundedEmptySectionsWhenAllMemorySourcesFail() {
        MemoryContextBuilder builder = builder(
                maxItems -> {
                    throw new IllegalStateException("preference unavailable");
                },
                (skuIds, maxItems) -> {
                    throw new IllegalStateException("sku history unavailable");
                },
                (query, filter, topN, asOf) -> {
                    throw new IllegalStateException("insight unavailable");
                });

        MemoryContext context = builder.build(request(50));

        assertThat(context.degraded()).isTrue();
        assertThat(context.sections()).hasSize(3).allMatch(section -> section.items().isEmpty());
        assertThat(context.section(MemoryContextBuilder.ANALYSIS_INSIGHTS).trace())
                .containsEntry("dependency_status", "unavailable")
                .containsEntry("degraded_reasons", List.of("insight_unavailable"));
        assertThat(context.sections().stream().mapToInt(MemorySection::tokenEstimate).sum())
                .isLessThanOrEqualTo(50);
    }

    private static MemoryContextBuilder builder(
            Clock clock,
            List<MemoryItem> preferences,
            List<MemoryItem> skuHistory,
            com.pixflow.module.memory.insight.InsightRecallService insightRecall) {
        MemoryProperties properties = new MemoryProperties();
        return new MemoryContextBuilder(
                new RecallSignalExtractor(),
                new RecallPlanner(properties),
                references -> new ResolvedRecallReferences(List.of("SKU-A"), List.of(), List.of()),
                maxItems -> preferences,
                (skuIds, maxItems) -> skuHistory,
                insightRecall,
                clock);
    }

    private static MemoryContextBuilder builder(
            com.pixflow.module.memory.preference.PreferenceService preferenceService,
            com.pixflow.module.memory.skuhistory.SkuHistoryService skuHistoryService,
            com.pixflow.module.memory.insight.InsightRecallService insightRecall) {
        MemoryProperties properties = new MemoryProperties();
        return new MemoryContextBuilder(
                new RecallSignalExtractor(),
                new RecallPlanner(properties),
                references -> new ResolvedRecallReferences(List.of("SKU-A"), List.of(), List.of()),
                preferenceService,
                skuHistoryService,
                insightRecall,
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));
    }

    private static MemoryContextRequest request(int budget) {
        return new MemoryContextRequest(
                "conv", 1, "trace", "请优化主图点击率", List.of(), List.of(), Map.of(), budget);
    }

    private static MemoryItem item(String id, MemoryType type, String text) {
        return new MemoryItem(id, type, text, "source", "主图", "SKU-A", 1, 1,
                1, 1, 1, null, null, Map.of());
    }
}
