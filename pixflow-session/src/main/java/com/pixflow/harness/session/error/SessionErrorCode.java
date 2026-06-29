package com.pixflow.harness.session.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum SessionErrorCode implements ErrorCode {
    SESSION_TRANSCRIPT_CORRUPTED("SESSION_TRANSCRIPT_CORRUPTED", ErrorCategory.INTERNAL),
    SESSION_SEQ_ALLOCATION_EXHAUSTED("SESSION_SEQ_ALLOCATION_EXHAUSTED", ErrorCategory.DEPENDENCY);

    private final String code;
    private final ErrorCategory category;

    SessionErrorCode(String code, ErrorCategory category) {
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
