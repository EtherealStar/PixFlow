package com.pixflow.module.conversation.error;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorCode;

public enum ConversationErrorCode implements ErrorCode {
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", ErrorCategory.NOT_FOUND),
    CONVERSATION_BUSY("CONVERSATION_BUSY", ErrorCategory.BUSINESS_RULE),
    CONVERSATION_TITLE_INVALID("CONVERSATION_TITLE_INVALID", ErrorCategory.VALIDATION),
    PROPOSAL_NOT_FOUND("PROPOSAL_NOT_FOUND", ErrorCategory.NOT_FOUND),
    PROPOSAL_ALREADY_CONFIRMED("PROPOSAL_ALREADY_CONFIRMED", ErrorCategory.BUSINESS_RULE),
    PROPOSAL_PAYLOAD_MISMATCH("PROPOSAL_PAYLOAD_MISMATCH", ErrorCategory.PERMISSION),
    LOCK_ACQUISITION_FAILED("LOCK_ACQUISITION_FAILED", ErrorCategory.DEPENDENCY),
    MESSAGE_REFERENCE_INVALID("MESSAGE_REFERENCE_INVALID", ErrorCategory.VALIDATION),
    TURN_RUNNER_UNAVAILABLE("TURN_RUNNER_UNAVAILABLE", ErrorCategory.DEPENDENCY),
    TURN_CAPACITY_EXCEEDED("TURN_CAPACITY_EXCEEDED", ErrorCategory.DEPENDENCY),
    TASK_EXECUTION_UNAVAILABLE("TASK_EXECUTION_UNAVAILABLE", ErrorCategory.DEPENDENCY),
    TASK_ID_INVALID("TASK_ID_INVALID", ErrorCategory.VALIDATION),
    TASK_NOT_FOUND("TASK_NOT_FOUND", ErrorCategory.NOT_FOUND),
    HISTORY_PAGE_INVALID("HISTORY_PAGE_INVALID", ErrorCategory.VALIDATION);

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
