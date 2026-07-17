package com.pixflow.harness.permission;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum PermissionErrorCode implements ErrorCode {
    PERMISSION_UNAUTHENTICATED,
    PERMISSION_ADMIN_INELIGIBLE,
    PERMISSION_SCOPE_DENIED,
    PERMISSION_PLAN_MODE_DENIED,
    PERMISSION_CONVERSATION_DENIED,
    PERMISSION_ASSET_DENIED,
    PERMISSION_PROPOSAL_DENIED,
    PERMISSION_TASK_DENIED;

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return ErrorCategory.PERMISSION;
    }
}
