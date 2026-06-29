package com.pixflow.module.vision.analyze;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.module.vision.error.VisionErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisionAnalysisRequestValidatorTest {
    private final VisionAnalysisRequestValidator validator = new VisionAnalysisRequestValidator();

    @Test
    void emptyRequestThrowsVisionEmptyRequest() {
        assertThatThrownBy(() -> validator.validate(VisionAnalysisRequest.of(List.of(), "q", VisionTaskType.DESCRIBE)))
                .isInstanceOfSatisfying(PixFlowException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.code()).isEqualTo(VisionErrorCode.VISION_EMPTY_REQUEST));
    }

    @Test
    void nonEmptyRequestPasses() {
        validator.validate(VisionAnalysisRequest.of(
                List.of(VisionImageRef.of(BucketType.PACKAGES, "1/images/a.png", "sku", "main", "主图")),
                "q",
                VisionTaskType.DESCRIBE));
    }
}
