package com.pixflow.module.vision.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import org.junit.jupiter.api.Test;

class VisionErrorCodeTest {

    @Test
    void codesAreUniqueAndUseTheVisionNamespace() {
        long distinctCodes = java.util.Arrays.stream(VisionErrorCode.values())
                .map(VisionErrorCode::code)
                .distinct()
                .count();

        assertThat(distinctCodes).isEqualTo(VisionErrorCode.values().length);
        assertThat(java.util.Arrays.stream(VisionErrorCode.values()).map(VisionErrorCode::code))
                .allMatch(code -> code.startsWith("VISION_") || code.startsWith("VISUAL_"));
    }

    @Test
    void categoriesMatchDesign() {
        assertThat(VisionErrorCode.VISUAL_FACTS_VERSION_CONFLICT.category())
                .isEqualTo(ErrorCategory.BUSINESS_RULE);
        assertThat(VisionErrorCode.VISUAL_ANALYSIS_GENERATION_CONFLICT.category())
                .isEqualTo(ErrorCategory.BUSINESS_RULE);
    }

}
