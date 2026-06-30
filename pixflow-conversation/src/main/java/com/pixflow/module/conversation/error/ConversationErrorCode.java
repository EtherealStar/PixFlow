package com.pixflow.module.conversation.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum ConversationErrorCode implements ErrorCode {
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", ErrorCategory.NOT_FOUND),
    CONVERSATION_ARCHIVED("CONVERSATION_ARCHIVED", ErrorCategory.BUSINESS_RULE),
    CONVERSATION_TITLE_INVALID("CONVERSATION_TITLE_INVALID", ErrorCategory.VALIDATION),
    PROPOSAL_NOT_FOUND("PROPOSAL_NOT_FOUND", ErrorCategory.NOT_FOUND),
    PROPOSAL_ALREADY_CONFIRMED("PROPOSAL_ALREADY_CONFIRMED", ErrorCategory.BUSINESS_RULE),
    PROPOSAL_CHALLENGE_EXPIRED("PROPOSAL_CHALLENGE_EXPIRED", ErrorCategory.BUSINESS_RULE),
    PROPOSAL_CHALLENGE_FAILED("PROPOSAL_CHALLENGE_FAILED", ErrorCategory.VALIDATION),
    CONFIRMATION_TOKEN_INVALID("CONFIRMATION_TOKEN_INVALID", ErrorCategory.PERMISSION),
    LOCK_ACQUISITION_FAILED("LOCK_ACQUISITION_FAILED", ErrorCategory.DEPENDENCY),
    ATTACHMENT_INVALID("ATTACHMENT_INVALID", ErrorCategory.VALIDATION),
    PACKAGE_REFERENCE_INVALID("PACKAGE_REFERENCE_INVALID", ErrorCategory.NOT_FOUND),
    TURN_RUNNER_UNAVAILABLE("TURN_RUNNER_UNAVAILABLE", ErrorCategory.DEPENDENCY),
    TASK_EXECUTION_UNAVAILABLE("TASK_EXECUTION_UNAVAILABLE", ErrorCategory.DEPENDENCY);

    private final String code;
    private final ErrorCategory category;

    ConversationErrorCode(String code, ErrorCategory category) {
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
