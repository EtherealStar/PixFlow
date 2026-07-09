package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 动态 section 10：可用技能目录（available_skills）。
 *
 * <p>对应 {@code agent.md §5.8} —— 渲染所有已注册 skill 的 name + description + when_to_use；
 * 不含 body（body 由 `skill__<name>` 工具按需取）。
 *
 * <p>fingerprint = sha256(sorted(name + description + version))。
 *
 * <p>如何识别 skill 工具：{@code visibleTools} 中 name 以 {@code skill__} 开头的即为 skill。
 */
@Component
public final class ActiveSkillsSection implements SectionRenderer {

    private static final String SKILL_PREFIX = "skill__";

    @Override
    public String key() {
        return "available_skills";
    }

    @Override
    public String title() {
        return "可用技能";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        if (ctx.visibleTools() == null || ctx.visibleTools().isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        // 按 name 排序后渲染（确定性 fingerprint）
        var sorted = ctx.visibleTools().stream()
                .filter(d -> d.name().startsWith(SKILL_PREFIX))
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
        if (sorted.isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        StringBuilder body = new StringBuilder();
        StringBuilder fpSeed = new StringBuilder();
        for (var desc : sorted) {
            String skillName = desc.name().substring(SKILL_PREFIX.length());
            body.append("### ").append(skillName).append('\n');
            body.append(desc.description()).append('\n');
            String prompt = desc.prompt() == null ? "" : desc.prompt();
            body.append("调用 `").append(desc.name()).append("` 获取完整规范正文\n\n");
            fpSeed.append(skillName).append("|").append(desc.description()).append("|").append(prompt).append('\n');
        }
        String text = body.toString().strip();
        String fingerprint = sha256(fpSeed.toString());
        return new PromptSection(key(), title(), text, fingerprint, true);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
