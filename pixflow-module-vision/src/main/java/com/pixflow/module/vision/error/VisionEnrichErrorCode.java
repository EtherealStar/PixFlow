package com.pixflow.module.vision.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum VisionEnrichErrorCode implements ErrorCode {
    VISION_COPY_EXTRACTION_FAILED(ErrorCategory.PROVIDER),
    VISION_FILL_POLICY_REJECTED(ErrorCategory.BUSINESS_RULE);

    private final ErrorCategory category;

    VisionEnrichErrorCode(ErrorCategory category) {
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
