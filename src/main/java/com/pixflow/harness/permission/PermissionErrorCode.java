package com.pixflow.harness.permission;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

/**
 * permission 模块专属错误码。
 */
public enum PermissionErrorCode implements ErrorCode {
    PERMISSION_DENIED,
    CONFIRMATION_TOKEN_MISSING,
    CONFIRMATION_TOKEN_INVALID,
    CONFIRMATION_TOKEN_EXPIRED,
    CONFIRMATION_PAYLOAD_MISMATCH,
    CONFIRMATION_COUNT_MISMATCH,
    BULK_CONFIRMATION_REQUIRED,
    SUBAGENT_FORBIDDEN_ACTION;

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return ErrorCategory.PERMISSION;
    }
}
