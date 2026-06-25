package com.etherealstar.pixflow.common.error;

import java.util.Map;

/**
 * 业务异常：携带 {@link ErrorCode} 与可选的覆盖消息及 details 上下文。
 *
 * <p>各业务模块在校验失败时抛出本异常，由 {@link GlobalExceptionHandler} 统一转换为
 * {@link ErrorResponse} 与对应 HTTP 状态码返回。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.getDefaultMessage(), details);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message != null ? message : errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public ErrorResponse toResponse() {
        return ErrorResponse.of(errorCode, getMessage(), details);
    }
}
