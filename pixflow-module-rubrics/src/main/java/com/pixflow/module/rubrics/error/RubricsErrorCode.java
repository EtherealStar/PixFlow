package com.pixflow.module.rubrics.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum RubricsErrorCode implements ErrorCode {
    RUBRICS_TEMPLATE_NOT_FOUND(ErrorCategory.NOT_FOUND),
    RUBRICS_JUDGE_TIMEOUT(ErrorCategory.DEPENDENCY),
    RUBRICS_JUDGE_PARSE_FAIL(ErrorCategory.VALIDATION),
    RUBRICS_RULE_VERIFIER_INIT_FAIL(ErrorCategory.INTERNAL),
    RUBRICS_BASELINE_NOT_FOUND(ErrorCategory.NOT_FOUND),
    RUBRICS_RUN_CANCELLED(ErrorCategory.BUSINESS_RULE);

    private final ErrorCategory category;

    RubricsErrorCode(ErrorCategory category) {
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
