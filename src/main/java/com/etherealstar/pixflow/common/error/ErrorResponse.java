package com.etherealstar.pixflow.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 统一错误响应结构：{@code {code, message, details}}。
 *
 * <p>所有接口错误均以该结构返回，便于前端统一展示与测试断言（design.md「Error Handling」）。
 * {@code details} 为可选的附加上下文（如约束上限值、失败节点 id 等），为空时不序列化输出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final Map<String, Object> details;

    public ErrorResponse(String code, String message, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.details = (details == null || details.isEmpty()) ? null : details;
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ErrorResponse(errorCode.name(), message, details);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
