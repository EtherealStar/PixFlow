package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import com.pixflow.module.memory.context.MemoryContextBuilder;
import com.pixflow.module.memory.context.MemorySection;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 动态 section 8：长期记忆（long_term_memory）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 8 行；
 * 渲染 module-memory 已融合后的 SKU 历史 + 分析结论子段。
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
        if (ctx.memoryContext() == null) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        StringBuilder body = new StringBuilder();
        for (String name : List.of(MemoryContextBuilder.SKU_HISTORY, MemoryContextBuilder.ANALYSIS_INSIGHTS)) {
            MemorySection section = ctx.memoryContext().section(name);
            if (section == null || section.renderedText().isBlank()) {
                continue;
            }
            body.append("### ").append(displayName(name)).append('\n');
            body.append(section.renderedText().strip()).append('\n');
            body.append('\n');
        }
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        String fingerprint = sha256(text + ":" + ctx.memoryContext().recallTrace());
        return new PromptSection(key(), title(), text, fingerprint, true);
    }

    private static String displayName(String sectionName) {
        return switch (sectionName) {
            case MemoryContextBuilder.SKU_HISTORY -> "SKU 处理历史";
            case MemoryContextBuilder.ANALYSIS_INSIGHTS -> "分析结论";
            default -> sectionName;
        };
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
