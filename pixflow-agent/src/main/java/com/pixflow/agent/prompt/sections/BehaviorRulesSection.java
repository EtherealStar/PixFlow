package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 固定 section 2：行为准则（behavior_rules）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 2 行。
 * fingerprint = sectionVersion（"v1"），cacheable = true（节省构造时间）。
 */
@Component
public final class BehaviorRulesSection implements SectionRenderer {

    private static final String SECTION_VERSION = "v1";

    private static final String BODY;

    static {
        try {
            BODY = new String(
                    new ClassPathResource("prompts/behavior_rules.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load behavior_rules.md", e);
        }
    }

    @Override
    public String key() {
        return "behavior_rules";
    }

    @Override
    public String title() {
        return "行为准则";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        return new PromptSection(key(), title(), BODY, SECTION_VERSION, true);
    }
}
