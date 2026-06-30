package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 固定 section 4：风险与安全（含 HITL 强规则）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 4 行。
 * fingerprint = sectionVersion（"v1"）；内容含"HITL 强规则"，高优先级。
 */
@Component
public final class RiskAndSafetySection implements SectionRenderer {

    private static final String SECTION_VERSION = "v1";
    private static final String BODY;

    static {
        try {
            BODY = new String(
                    new ClassPathResource("prompts/risk_and_safety.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load risk_and_safety.md", e);
        }
    }

    @Override
    public String key() {
        return "risk_and_safety";
    }

    @Override
    public String title() {
        return "风险与安全";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        return new PromptSection(key(), title(), BODY, SECTION_VERSION, true);
    }
}