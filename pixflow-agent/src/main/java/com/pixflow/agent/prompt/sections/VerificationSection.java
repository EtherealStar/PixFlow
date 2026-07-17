package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 固定 section 5：验证与汇报（verification_and_reporting）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 5 行。
 * fingerprint = sectionVersion（"v1"）。
 */
@Component
public final class VerificationSection implements SectionRenderer {

    private static final String SECTION_VERSION = "v1";

    private static final String BODY;

    static {
        try {
            BODY = new String(
                    new ClassPathResource("prompts/verification_and_reporting.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load verification_and_reporting.md", e);
        }
    }

    @Override
    public String key() {
        return "verification_and_reporting";
    }

    @Override
    public String title() {
        return "验证与汇报";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        return new PromptSection(key(), title(), BODY, SECTION_VERSION, true);
    }
}
