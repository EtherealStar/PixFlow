package com.pixflow.harness.state.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum StateErrorCode implements ErrorCode {
    STATE_TASK_NOT_FOUND(ErrorCategory.NOT_FOUND),
    STATE_TASK_CANCELLED(ErrorCategory.BUSINESS_RULE);

    private final ErrorCategory category;

    StateErrorCode(ErrorCategory category) {
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
