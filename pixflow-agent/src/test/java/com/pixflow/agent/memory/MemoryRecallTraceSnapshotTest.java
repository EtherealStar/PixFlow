package com.pixflow.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemorySection;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryRecallTraceSnapshotTest {

    @Test
    void snapshotKeepsBoundedStatisticsWithoutRecallTextOrQueries() {
        MemorySection insight = new MemorySection(
                MemoryContextBuilder.ANALYSIS_INSIGHTS,
                "分析结论:\n- 不能进入 trace 的正文",
                List.of(item()),
                15,
                Map.of(
                        "requested_item_count", 7,
                        "selected_item_count", 1,
                        "degraded_reasons", List.of("vector_unavailable"),
                        "query", "不能进入 trace 的原始查询"));
        MemoryContext context = new MemoryContext(
                "conversation-secret",
                3,
                List.of(insight),
                Map.of(
                        "token_budget", 4000,
                        "used_tokens", 15,
                        "signals", Map.of("sku_ids", List.of("SKU-A"), "categories", List.of("包")),
                        "reference_resolution", List.of(
                                Map.of("status", "resolution_failed", "reference_key", "private-reference"),
                                Map.of("status", "truncated", "reference_key", "private-package"))),
                true);

        Map<String, Object> snapshot = MemoryRecallTraceSnapshot.from(context);

        assertThat(snapshot).containsEntry("degraded", true)
                .containsEntry("token_budget", 4000)
                .containsEntry("used_tokens", 15)
                .containsEntry("sku_filter_count", 1)
                .containsEntry("category_filter_count", 1)
                .containsEntry("degraded_reference_count", 2)
                .containsEntry("truncated_reference_count", 1)
                .containsEntry("reference_degradation_counts", Map.of(
                        "resolution_failed", 1,
                        "truncated", 1));
        assertThat(snapshot.toString())
                .contains("vector_unavailable")
                .doesNotContain("不能进入 trace")
                .doesNotContain("conversation-secret")
                .doesNotContain("private-reference")
                .doesNotContain("private-package")
                .doesNotContain("SKU-A");
    }

    private static MemoryItem item() {
        return new MemoryItem("insight-1", MemoryType.INSIGHT, "secret", "source", "", "",
                1, 1, 1, 1, 1, null, null, Map.of());
    }
}
