package com.pixflow.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSectionTest {

    @Test
    void render_includes_title_and_body() {
        PromptSection section = new PromptSection(
                "test_key", "Test Title", "test body", "v1", true);
        assertEquals("# Test Title\ntest body", section.render());
    }

    @Test
    void render_returns_empty_string_when_body_is_blank() {
        PromptSection section = new PromptSection(
                "test_key", "Test Title", "", "v1", true);
        assertEquals("", section.render());
    }

    @Test
    void cacheable_flag_round_trips() {
        PromptSection cacheable = new PromptSection("k", "t", "b", "v1", true);
        PromptSection nonCacheable = new PromptSection("k", "t", "b", "v1", false);
        assertTrue(cacheable.cacheable());
        assertEquals(false, nonCacheable.cacheable());
    }
}