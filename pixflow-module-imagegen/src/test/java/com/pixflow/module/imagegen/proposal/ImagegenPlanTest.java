package com.pixflow.module.imagegen.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImagegenPlanTest {

    @Test
    void constructor_preservesPrompt() {
        ImagegenPlan plan = new ImagegenPlan(
            List.of("image-1"), "  已规范化的 prompt  ", Map.of(), null, "conv-1", "pkg-1");

        assertThat(plan.prompt()).isEqualTo("  已规范化的 prompt  ");
    }

    @Test
    void constructor_rejectsNullPrompt() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ImagegenPlan(
                List.of("image-1"), null, Map.of(), null, "conv-1", "pkg-1"))
            .withMessage("prompt");
    }
}
