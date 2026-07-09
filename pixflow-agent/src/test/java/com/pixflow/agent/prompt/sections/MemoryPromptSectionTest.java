package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.planmode.PlanModeState;
import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemorySection;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPromptSectionTest {

    @Test
    void preference_section_renders_user_preferences_rendered_text() {
        MemorySection preference = new MemorySection(
                MemoryContextBuilder.USER_PREFERENCES,
                "偏好:\n- 喜欢白底图",
                List.of(item("pref-1", MemoryType.PREFERENCE, "喜欢白底图")),
                8,
                Map.of("source", "test"));
        PreferenceSection section = new PreferenceSection();

        PromptSection rendered = section.render(ctx(new MemoryContext(
                "conv-1", 1, List.of(preference), Map.of(), false)));

        assertEquals("instruction_memory", rendered.key());
        assertEquals("偏好:\n- 喜欢白底图", rendered.body());
        assertFalse(rendered.fingerprint().isBlank());
    }

    @Test
    void long_term_section_renders_sku_history_and_analysis_insights() {
        MemorySection skuHistory = new MemorySection(
                MemoryContextBuilder.SKU_HISTORY,
                "SKU历史:\n- SKU-A 最近点击率提升",
                List.of(item("sku-1", MemoryType.SKU_HISTORY, "SKU-A 最近点击率提升")),
                12,
                Map.of());
        MemorySection insights = new MemorySection(
                MemoryContextBuilder.ANALYSIS_INSIGHTS,
                "分析结论:\n- 白底主图更适合首图",
                List.of(item("insight-1", MemoryType.INSIGHT, "白底主图更适合首图")),
                12,
                Map.of("rrf", true));
        LongTermMemorySection section = new LongTermMemorySection();

        PromptSection rendered = section.render(ctx(new MemoryContext(
                "conv-1", 1, List.of(skuHistory, insights), Map.of(), false)));

        assertEquals("long_term_memory", rendered.key());
        assertTrue(rendered.body().contains("### SKU 处理历史"));
        assertTrue(rendered.body().contains("SKU-A 最近点击率提升"));
        assertTrue(rendered.body().contains("### 分析结论"));
        assertTrue(rendered.body().contains("白底主图更适合首图"));
        assertFalse(rendered.body().contains("insights.vector"));
        assertFalse(rendered.body().contains("insights.fulltext"));
    }

    @Test
    void long_term_section_skips_empty_memory_sections() {
        LongTermMemorySection section = new LongTermMemorySection();

        PromptSection rendered = section.render(ctx(new MemoryContext(
                "conv-1",
                1,
                List.of(new MemorySection(MemoryContextBuilder.SKU_HISTORY, "", List.of(), 0, Map.of())),
                Map.of(),
                false)));

        assertEquals("", rendered.body());
        assertEquals("empty", rendered.fingerprint());
    }

    private static SectionRenderer.PromptRuntimeContext ctx(MemoryContext memoryContext) {
        RuntimeState state = new RuntimeState();
        state.setConversationId("conv-1");
        state.setTraceId("trace-1");
        state.setTurnNo(1);
        return new SectionRenderer.PromptRuntimeContext(
                state,
                "conv-1",
                1,
                null,
                List.of(),
                List.of(),
                List.of(),
                "user prompt",
                PlanModeState.OFF,
                memoryContext,
                null,
                List.of());
    }

    private static MemoryItem item(String id, MemoryType type, String text) {
        return new MemoryItem(id, type, text, "test", "", "", 1.0, 1.0,
                1.0, 1.0, 1.0, null, null, Map.of());
    }
}
