package com.pixflow.common.error;

import java.util.Map;

/**
 * 面向业务规则违例的语义糖，避免服务层直接抛裸 RuntimeException。
 */
public class BusinessException extends PixFlowException {
    public BusinessException(ErrorCode code, String message) {
        super(requireBusinessCode(code), message);
    }

    public BusinessException(ErrorCode code, String message, Map<String, ?> details) {
        super(requireBusinessCode(code), message, null, details);
    }

    public BusinessException(ErrorCode code, String message, Throwable cause, Map<String, ?> details) {
        super(requireBusinessCode(code), message, cause, details);
    }

    private static ErrorCode requireBusinessCode(ErrorCode code) {
        // 这里收窄语义，防止业务异常被误用成基础设施兜底异常。
        if (code.category() != ErrorCategory.VALIDATION
                && code.category() != ErrorCategory.BUSINESS_RULE
                && code.category() != ErrorCategory.NOT_FOUND
                && code.category() != ErrorCategory.PERMISSION) {
            throw new IllegalArgumentException("BusinessException 只能使用 4xx 业务类错误码");
        }
        return code;
    }
}
