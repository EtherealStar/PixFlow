package com.pixflow.module.commerce.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum CommerceErrorCode implements ErrorCode {
    COMMERCE_IMPORT_FORMAT_UNSUPPORTED(ErrorCategory.VALIDATION),
    COMMERCE_IMPORT_MISSING_COLUMN(ErrorCategory.VALIDATION),
    COMMERCE_IMPORT_FILE_CORRUPTED(ErrorCategory.VALIDATION),
    COMMERCE_INVALID_METRIC(ErrorCategory.VALIDATION),
    COMMERCE_IMPORT_JOB_NOT_FOUND(ErrorCategory.NOT_FOUND),
    COMMERCE_PLATFORM_PULL_FAILED(ErrorCategory.PROVIDER),
    COMMERCE_MYSQL_UNAVAILABLE(ErrorCategory.DEPENDENCY);

    private final ErrorCategory category;

    CommerceErrorCode(ErrorCategory category) {
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
