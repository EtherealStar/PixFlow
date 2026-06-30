package com.pixflow.agent.prompt.sections;

import com.pixflow.agent.prompt.PromptSection;
import com.pixflow.agent.prompt.SectionRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 固定 section 1：身份定义（identity）。
 *
 * <p>对应 {@code agent.md §4.1} 表第 1 行。
 * 内容从 classpath 资源 {@code prompts/identity.md} 加载；
 * fingerprint = sectionVersion（"v1"），无运行时依赖。
 */
@Component
public final class IdentitySection implements SectionRenderer {

    private static final String SECTION_VERSION = "v1";
    private static final String BODY;

    static {
        try {
            BODY = new String(
                    new ClassPathResource("prompts/identity.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load identity.md", e);
        }
    }

    @Override
    public String key() {
        return "identity";
    }

    @Override
    public String title() {
        return "身份与定位";
    }

    @Override
    public PromptSection render(SectionRenderer.PromptRuntimeContext ctx) {
        return new PromptSection(key(), title(), BODY, SECTION_VERSION, true);
    }
}