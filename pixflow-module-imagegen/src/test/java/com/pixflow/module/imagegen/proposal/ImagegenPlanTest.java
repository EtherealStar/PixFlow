package com.pixflow.module.imagegen.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ImagegenPlanTest {

    @Test
    void constructor_preservesPrompt() {
        ImagegenPlan plan = new ImagegenPlan(
            "package:7/image:1", "  已规范化的 prompt  ", Map.of(), null, "conv-1", 7L);

        assertThat(plan.prompt()).isEqualTo("  已规范化的 prompt  ");
    }

    @Test
    void constructor_rejectsNullPrompt() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ImagegenPlan(
                "package:7/image:1", null, Map.of(), null, "conv-1", 7L))
            .withMessage("prompt");
    }

    @Test
    void constructorRejectsMismatchedPackageIdentity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImagegenPlan(
                        "package:7/image:1", "prompt", Map.of(), null, "conv-1", 8L))
                .withMessageContaining("packageId");
    }

    @Test
    void constructorRejectsNonImageReference() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImagegenPlan(
                        "package:7", "prompt", Map.of(), null, "conv-1", 7L))
                .withMessageContaining("IMAGE");
    }
}
