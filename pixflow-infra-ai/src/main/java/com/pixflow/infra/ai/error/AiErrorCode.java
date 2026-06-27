package com.pixflow.infra.ai.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * infra/ai 专属错误码。
 */
public enum AiErrorCode implements ErrorCode {
    MODEL_RATE_LIMITED(ErrorCategory.RATE_LIMIT),
    MODEL_CONTEXT_LIMIT(ErrorCategory.CONTEXT_LIMIT),
    MODEL_NETWORK_ERROR(ErrorCategory.NETWORK),
    MODEL_PROVIDER_ERROR(ErrorCategory.PROVIDER),
    MODEL_AUTH_ERROR(ErrorCategory.PROVIDER),
    INVALID_TOOL_ARGUMENTS(ErrorCategory.PROVIDER),
    MODEL_CONFIGURATION_ERROR(ErrorCategory.DEPENDENCY),
    MODEL_UNSUPPORTED_CAPABILITY(ErrorCategory.DEPENDENCY);

    private final ErrorCategory category;

    AiErrorCode(ErrorCategory category) {
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
