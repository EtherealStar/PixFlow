package com.pixflow.infra.thirdparty.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum ThirdPartyErrorCode implements ErrorCode {
    THIRDPARTY_PROVIDER_NOT_CONFIGURED(ErrorCategory.DEPENDENCY),
    THIRDPARTY_RATE_LIMITED(ErrorCategory.RATE_LIMIT),
    THIRDPARTY_NETWORK_ERROR(ErrorCategory.NETWORK),
    THIRDPARTY_PROVIDER_ERROR(ErrorCategory.PROVIDER),
    THIRDPARTY_PROVIDER_TIMEOUT(ErrorCategory.NETWORK),
    THIRDPARTY_CIRCUIT_OPEN(ErrorCategory.PROVIDER),
    THIRDPARTY_INVALID_REQUEST(ErrorCategory.VALIDATION),
    THIRDPARTY_AUTH_ERROR(ErrorCategory.PROVIDER),
    THIRDPARTY_RESPONSE_INVALID(ErrorCategory.PROVIDER);

    private final ErrorCategory category;

    ThirdPartyErrorCode(ErrorCategory category) {
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
