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

/**
 * 动态 section 6：用户偏好画像（instruction_memory）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 6 行；来源是 module-memory 统一产出的
 * {@code user_preferences} section。
 *
 * <p>永远触发（每轮都从 user_preference 表全量读）。
 */
@Component
public final class PreferenceSection implements SectionRenderer {

    @Override
    public String key() {
        return "instruction_memory";
    }

    @Override
    public String title() {
        return "用户偏好";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        if (ctx.memoryContext() == null) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        MemorySection section = ctx.memoryContext().section(MemoryContextBuilder.USER_PREFERENCES);
        if (section == null || section.renderedText().isBlank()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        String fingerprint = sha256(section.name()
                + ":" + section.tokenEstimate()
                + ":" + section.renderedText()
                + ":" + section.trace());
        return new PromptSection(key(), title(), section.renderedText().strip(), fingerprint, true);
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
