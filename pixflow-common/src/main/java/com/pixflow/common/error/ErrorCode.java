package com.pixflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码契约：模块后续只需要实现这个接口，不必再重新定义一套错误体系。
 */
public interface ErrorCode {
    String code();

    ErrorCategory category();

    default HttpStatus httpStatus() {
        return category().defaultHttpStatus();
    }

    default String messageKey() {
        return code();
    }
}
