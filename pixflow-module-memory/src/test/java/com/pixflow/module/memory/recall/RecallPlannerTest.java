package com.pixflow.module.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.memory.config.MemoryProperties;
import com.pixflow.module.memory.context.MemoryAttachment;
import com.pixflow.module.memory.context.MemoryContextRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecallPlannerTest {

    @Test
    void plansPreferenceEveryTurnAndSkuHistoryWhenSkuAppears() {
        RecallSignalExtractor extractor = new RecallSignalExtractor();
        RecallSignals signals = extractor.extract(new MemoryContextRequest(
                "c1",
                1,
                "t1",
                "帮我处理 SKU123 的连衣裙主图，优先提升点击率",
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                null));

        RecallPlan plan = new RecallPlanner(new MemoryProperties()).plan(request(), signals);

        assertThat(plan.recallPreference()).isTrue();
        assertThat(plan.recallSkuHistory()).isTrue();
        assertThat(plan.recallInsight()).isTrue();
        assertThat(plan.skuIds()).contains("SKU123");
        assertThat(plan.insightQuery()).contains("连衣裙", "点击率");
    }

    @Test
    void extractsSkuFromAttachmentAndReducesInsightTopNWhenBudgetIsTight() {
        MemoryContextRequest request = new MemoryContextRequest(
                "c1",
                1,
                "t1",
                "换白底",
                List.of(new MemoryAttachment("SKU999_main.png", null, "家居", Map.of())),
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                500);

        RecallSignals signals = new RecallSignalExtractor().extract(request);
        RecallPlan plan = new RecallPlanner(new MemoryProperties()).plan(request, signals);

        assertThat(signals.skuIds()).contains("SKU999");
        assertThat(plan.recallSkuHistory()).isTrue();
        assertThat(plan.insightTopN()).isLessThan(new MemoryProperties().getPrompt().getInsightTopn());
    }

    private static MemoryContextRequest request() {
        return new MemoryContextRequest("c1", 1, "t1", "", List.of(), null, null, List.of(), List.of(), Map.of(), null);
    }
}
