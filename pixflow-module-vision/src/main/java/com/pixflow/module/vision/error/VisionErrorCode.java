package com.pixflow.module.vision.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum VisionErrorCode implements ErrorCode {
    VISUAL_FACTS_VERSION_CONFLICT(ErrorCategory.BUSINESS_RULE),
    VISUAL_ANALYSIS_GENERATION_CONFLICT(ErrorCategory.BUSINESS_RULE),
    VISUAL_ANALYSIS_ACTIVE(ErrorCategory.BUSINESS_RULE),
    VISUAL_PROVIDER_BUDGET_EXHAUSTED(ErrorCategory.BUSINESS_RULE),
    VISUAL_ANALYSIS_FENCED(ErrorCategory.BUSINESS_RULE),
    VISUAL_FACTS_INVALID(ErrorCategory.VALIDATION);

    private final ErrorCategory category;

    VisionErrorCode(ErrorCategory category) {
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
