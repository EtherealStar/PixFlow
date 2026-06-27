package com.pixflow.infra.ai.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import org.junit.jupiter.api.Test;

class AiErrorCodeTest {

    @Test
    void categoriesMatchDesign() {
        assertThat(AiErrorCode.MODEL_RATE_LIMITED.category()).isEqualTo(ErrorCategory.RATE_LIMIT);
        assertThat(AiErrorCode.MODEL_CONTEXT_LIMIT.category()).isEqualTo(ErrorCategory.CONTEXT_LIMIT);
        assertThat(AiErrorCode.MODEL_NETWORK_ERROR.category()).isEqualTo(ErrorCategory.NETWORK);
    }
}
