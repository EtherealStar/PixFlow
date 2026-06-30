package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import com.pixflow.agent.memory.MemorySection;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 动态 section 6：用户偏好画像（instruction_memory）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 6 行；对应 `MemoryRecallResult.section("user_preferences")`。
 *
 * <p>fingerprint = sha256(全部 MemoryItem 的 itemId + score 排序拼接)；
 * 内容渲染为编号列表（每条一行）。
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
        if (ctx.recall() == null) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        MemorySection section = ctx.recall().section("user_preferences");
        if (section.isEmpty()) {
            return new PromptSection(key(), title(), "", "empty", true);
        }
        StringBuilder body = new StringBuilder();
        section.items().forEach(item -> body.append("- ").append(item.preview()).append('\n'));
        String fingerprint = sha256(
                section.items().stream()
                        .map(i -> i.itemId() + ":" + i.score())
                        .sorted()
                        .reduce("", (a, b) -> a + "," + b)
        );
        return new PromptSection(key(), title(), body.toString().strip(), fingerprint, true);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}