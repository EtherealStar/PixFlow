package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import com.pixflow.agent.memory.MemorySection;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态 section 8：长期记忆（long_term_memory）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 8 行；
 * 渲染 RRF 融合后的 SKU 历史 + 分析结论子段。
 *
 * <p>fingerprint = "{recallPlanId}.{totalTokens}"（每轮新建的 UUID + token 总数）。
 * 空 sections → 跳过该段。
 */
@Component
public final class LongTermMemorySection implements SectionRenderer {

    @Override
    public String key() {
        return "long_term_memory";
    }

    @Override
    public String title() {
        return "长期记忆";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        if (ctx.recall() == null) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        StringBuilder body = new StringBuilder();
        for (String name : List.of("sku_history", "insights.vector", "insights.fulltext")) {
            MemorySection section = ctx.recall().section(name);
            if (section.isEmpty()) continue;
            body.append("### ").append(displayName(name)).append('\n');
            section.items().forEach(item ->
                    body.append("- ").append(item.preview()).append('\n'));
            body.append('\n');
        }
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        String fingerprint = ctx.recall().recallPlanId().toString() + "." + ctx.recall().totalTokens();
        return new PromptSection(key(), title(), text, fingerprint, true);
    }

    private static String displayName(String sectionName) {
        return switch (sectionName) {
            case "sku_history" -> "SKU 处理历史";
            case "insights.vector" -> "分析结论（向量召回）";
            case "insights.fulltext" -> "分析结论（全文召回）";
            default -> sectionName;
        };
    }
}