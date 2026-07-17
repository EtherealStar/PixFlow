package com.pixflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误分类轴，负责给出默认恢复策略和默认 HTTP 映射。
 */
public enum ErrorCategory {
    VALIDATION(RecoveryHint.TERMINATE, HttpStatus.BAD_REQUEST),
    BUSINESS_RULE(RecoveryHint.TERMINATE, HttpStatus.CONFLICT),
    NOT_FOUND(RecoveryHint.TERMINATE, HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(RecoveryHint.TERMINATE, HttpStatus.METHOD_NOT_ALLOWED),
    PERMISSION(RecoveryHint.TERMINATE, HttpStatus.FORBIDDEN),
    RATE_LIMIT(RecoveryHint.RETRY, HttpStatus.TOO_MANY_REQUESTS),
    NETWORK(RecoveryHint.RETRY, HttpStatus.GATEWAY_TIMEOUT),
    PROVIDER(RecoveryHint.RETRY, HttpStatus.BAD_GATEWAY),
    CONTEXT_LIMIT(RecoveryHint.COMPACT, HttpStatus.INTERNAL_SERVER_ERROR),
    STORAGE(RecoveryHint.RETRY, HttpStatus.INTERNAL_SERVER_ERROR),
    IMAGE_PROCESSING(RecoveryHint.SKIP, HttpStatus.INTERNAL_SERVER_ERROR),
    TOOL(RecoveryHint.SKIP, HttpStatus.INTERNAL_SERVER_ERROR),
    DEPENDENCY(RecoveryHint.RETRY, HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL(RecoveryHint.TERMINATE, HttpStatus.INTERNAL_SERVER_ERROR);

    private final RecoveryHint defaultRecovery;

    private final HttpStatus defaultHttpStatus;

    ErrorCategory(RecoveryHint defaultRecovery, HttpStatus defaultHttpStatus) {
        this.defaultRecovery = defaultRecovery;
        this.defaultHttpStatus = defaultHttpStatus;
    }

    public RecoveryHint defaultRecovery() {
        return defaultRecovery;
    }

    public HttpStatus defaultHttpStatus() {
        return defaultHttpStatus;
    }
}
