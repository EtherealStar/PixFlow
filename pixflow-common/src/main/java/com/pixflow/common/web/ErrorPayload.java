package com.pixflow.common.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败响应中的结构化错误体，便于前端与测试固定解析。
 */
public record ErrorPayload(String code, String message, Map<String, Object> details, String traceId) {
    public ErrorPayload {
        details = immutableCopy(details);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
