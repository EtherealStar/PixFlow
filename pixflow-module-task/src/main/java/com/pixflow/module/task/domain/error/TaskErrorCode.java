package com.pixflow.module.task.domain.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum TaskErrorCode implements ErrorCode {
    TASK_NOT_FOUND("TASK_NOT_FOUND", ErrorCategory.NOT_FOUND),
    TASK_ALREADY_COMPLETED("TASK_ALREADY_COMPLETED", ErrorCategory.BUSINESS_RULE),
    TASK_CREATE_FAILED("TASK_CREATE_FAILED", ErrorCategory.STORAGE),
    TASK_ENQUEUE_FAILED("TASK_ENQUEUE_FAILED", ErrorCategory.DEPENDENCY),
    TASK_DAG_PAYLOAD_INVALID("TASK_DAG_PAYLOAD_INVALID", ErrorCategory.VALIDATION),
    TASK_IMAGEGEN_PAYLOAD_INVALID("TASK_IMAGEGEN_PAYLOAD_INVALID", ErrorCategory.VALIDATION),
    TASK_ASSET_READ_FAILED("TASK_ASSET_READ_FAILED", ErrorCategory.DEPENDENCY),
    TASK_RESULT_WRITE_FAILED("TASK_RESULT_WRITE_FAILED", ErrorCategory.STORAGE),
    TASK_STATE_TRANSITION_REJECTED("TASK_STATE_TRANSITION_REJECTED", ErrorCategory.BUSINESS_RULE),
    TASK_RESULT_NOT_FOUND("TASK_RESULT_NOT_FOUND", ErrorCategory.NOT_FOUND),
    TASK_RESULT_NAME_INVALID("TASK_RESULT_NAME_INVALID", ErrorCategory.VALIDATION),
    TASK_DOWNLOAD_NOT_READY("TASK_DOWNLOAD_NOT_READY", ErrorCategory.BUSINESS_RULE),
    TASK_DOWNLOAD_BUNDLE_TOO_LARGE("TASK_DOWNLOAD_BUNDLE_TOO_LARGE", ErrorCategory.VALIDATION),
    TASK_WORKER_REJECTED("TASK_WORKER_REJECTED", ErrorCategory.DEPENDENCY),
    TASK_RETRY_SOURCE_NOT_TERMINAL("TASK_RETRY_SOURCE_NOT_TERMINAL", ErrorCategory.BUSINESS_RULE),
    TASK_NO_FAILED_UNITS("TASK_NO_FAILED_UNITS", ErrorCategory.BUSINESS_RULE),
    TASK_CANCELLED("TASK_CANCELLED", ErrorCategory.BUSINESS_RULE);

    private final String code;
    private final ErrorCategory category;

    TaskErrorCode(String code, ErrorCategory category) {
        this.code = code;
        this.category = category;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
