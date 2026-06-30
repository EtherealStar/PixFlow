package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 动态 section 11：工具 prompt 汇总（available_tools）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 11 行；
 * 聚合 visible tools 的 {@code prompt} 字段。
 *
 * <p>每个工具的 prompt 单独成段（与 `tool_prompt:<name>` 段同源），
 * 但本段聚合渲染——节省 token 但保留关键信息。
 *
 * <p>fingerprint = sha256(sorted(visibleTool.name + ":" + tool.prompt))。
 */
@Component
public final class ToolPromptSection implements SectionRenderer {

    @Override
    public String key() {
        return "available_tools";
    }

    @Override
    public String title() {
        return "工具 prompt 汇总";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        if (ctx.visibleTools() == null || ctx.visibleTools().isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        StringBuilder body = new StringBuilder();
        StringBuilder fpSeed = new StringBuilder();
        var sorted = ctx.visibleTools().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
        for (var desc : sorted) {
            String prompt = desc.prompt();
            if (prompt == null || prompt.isBlank()) continue;
            body.append("### ").append(desc.name()).append('\n');
            body.append(prompt).append("\n\n");
            fpSeed.append(desc.name()).append(":").append(prompt).append('\n');
        }
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        String fingerprint = sha256(fpSeed.toString());
        return new PromptSection(key(), title(), text, fingerprint, true);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}