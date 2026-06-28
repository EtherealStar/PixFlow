package com.pixflow.module.file.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum FileErrorCode implements ErrorCode {
    PACKAGE_NOT_FOUND(ErrorCategory.NOT_FOUND),
    INVALID_ZIP(ErrorCategory.VALIDATION),
    ZIP_BOMB_DETECTED(ErrorCategory.BUSINESS_RULE),
    ZIP_PATH_TRAVERSAL(ErrorCategory.BUSINESS_RULE),
    NO_VALID_IMAGE(ErrorCategory.BUSINESS_RULE),
    UNSUPPORTED_IMAGE_FORMAT(ErrorCategory.VALIDATION),
    COPY_DOC_PARSE_FAILED(ErrorCategory.VALIDATION),
    UPLOAD_TOO_LARGE(ErrorCategory.VALIDATION),
    PACKAGE_ALREADY_REFERENCED(ErrorCategory.BUSINESS_RULE),
    MESSAGE_PUBLISH_FAILED(ErrorCategory.DEPENDENCY);

    private final ErrorCategory category;

    FileErrorCode(ErrorCategory category) {
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
