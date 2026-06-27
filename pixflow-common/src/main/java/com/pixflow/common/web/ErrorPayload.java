package com.pixflow.common.web;

import java.util.Map;

/**
 * 失败响应中的结构化错误体，便于前端与测试固定解析。
 */
public record ErrorPayload(String code, String message, Map<String, Object> details, String traceId) {
}
