package com.pixflow.module.vision.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * vision 阶段 A 错误码。模型调用错误由 infra/ai 的 AiErrorCode 原样透传。
 */
public enum VisionErrorCode implements ErrorCode {
    VISION_NO_DECODABLE_IMAGE(ErrorCategory.VALIDATION),
    VISION_IMAGE_RESOLVE_FAILED(ErrorCategory.DEPENDENCY),
    VISION_IMAGE_TOO_LARGE(ErrorCategory.VALIDATION),
    VISION_EMPTY_REQUEST(ErrorCategory.VALIDATION);

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
