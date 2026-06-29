package com.pixflow.module.vision.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import org.junit.jupiter.api.Test;

class VisionErrorCodeTest {

    @Test
    void phaseACodesAreUniqueAndPrefixed() {
        assertThat(VisionErrorCode.values()).hasSize(4);
        assertThat(java.util.Arrays.stream(VisionErrorCode.values()).map(VisionErrorCode::code).distinct().count()).isEqualTo(4);
        assertThat(java.util.Arrays.stream(VisionErrorCode.values()).map(VisionErrorCode::code))
                .allMatch(code -> code.startsWith("VISION_"));
    }

    @Test
    void categoriesMatchDesign() {
        assertThat(VisionErrorCode.VISION_NO_DECODABLE_IMAGE.category()).isEqualTo(ErrorCategory.VALIDATION);
        assertThat(VisionErrorCode.VISION_IMAGE_RESOLVE_FAILED.category()).isEqualTo(ErrorCategory.DEPENDENCY);
        assertThat(VisionErrorCode.VISION_IMAGE_TOO_LARGE.category()).isEqualTo(ErrorCategory.VALIDATION);
        assertThat(VisionErrorCode.VISION_EMPTY_REQUEST.category()).isEqualTo(ErrorCategory.VALIDATION);
    }

    @Test
    void enrichCodesAreUniqueAndPrefixed() {
        assertThat(VisionEnrichErrorCode.values()).hasSize(2);
        assertThat(java.util.Arrays.stream(VisionEnrichErrorCode.values()).map(VisionEnrichErrorCode::code).distinct().count()).isEqualTo(2);
        assertThat(java.util.Arrays.stream(VisionEnrichErrorCode.values()).map(VisionEnrichErrorCode::code))
                .allMatch(code -> code.startsWith("VISION_"));
    }
}
