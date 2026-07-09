package com.pixflow.common.error;

/**
 * common 模块提供的通用错误码，后续模块可在此基础上继续扩展自己的专属码。
 */
public enum CommonErrorCode implements ErrorCode {
    INTERNAL_ERROR("INTERNAL_ERROR", ErrorCategory.INTERNAL),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", ErrorCategory.METHOD_NOT_ALLOWED),
    INVALID_PARAM("INVALID_PARAM", ErrorCategory.VALIDATION),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", ErrorCategory.NOT_FOUND),
    PERMISSION_DENIED("PERMISSION_DENIED", ErrorCategory.PERMISSION),
    BUSINESS_RULE_VIOLATION("BUSINESS_RULE_VIOLATION", ErrorCategory.BUSINESS_RULE),
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", ErrorCategory.DEPENDENCY),
    RATE_LIMITED("RATE_LIMITED", ErrorCategory.RATE_LIMIT),
    NETWORK_TIMEOUT("NETWORK_TIMEOUT", ErrorCategory.NETWORK),
    PROVIDER_FAILURE("PROVIDER_FAILURE", ErrorCategory.PROVIDER),
    CONTEXT_LIMIT_EXCEEDED("CONTEXT_LIMIT_EXCEEDED", ErrorCategory.CONTEXT_LIMIT),
    STORAGE_FAILURE("STORAGE_FAILURE", ErrorCategory.STORAGE),
    IMAGE_PROCESSING_FAILURE("IMAGE_PROCESSING_FAILURE", ErrorCategory.IMAGE_PROCESSING),
    TOOL_FAILURE("TOOL_FAILURE", ErrorCategory.TOOL);

    private final String code;
    private final ErrorCategory category;

    CommonErrorCode(String code, ErrorCategory category) {
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
