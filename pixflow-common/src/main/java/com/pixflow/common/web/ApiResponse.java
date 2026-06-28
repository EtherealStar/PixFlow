package com.pixflow.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应信封，成功和失败使用同一种外层结构。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Map<String, Object> details,
        String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", null, data, null, null);
    }

    public static <T> ApiResponse<T> error(PixFlowException error, String safeMessage) {
        String message = Sanitizer.sanitizeMessage(safeMessage);
        return new ApiResponse<>(false, error.code().code(), message, null, sanitizeDetails(error.details()), error.traceId());
    }

    public static <T> ApiResponse<T> error(String code, String message, Map<String, Object> details, String traceId) {
        return new ApiResponse<>(false, code, Sanitizer.sanitizeMessage(message), null, sanitizeDetails(details), traceId);
    }

    public static <T> ApiResponse<T> error(PixFlowException error) {
        return error(error, error.getMessage());
    }

    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, CommonErrorCode.INTERNAL_ERROR.code(), Sanitizer.sanitizeMessage(message), null, null, null);
    }

    private static Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return details;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(details.size());
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                sanitized.put(entry.getKey(), Sanitizer.sanitizeMessage(text));
            } else {
                sanitized.put(entry.getKey(), value);
            }
        }
        return sanitized;
    }
}
