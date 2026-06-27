package com.pixflow.common.error.render;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool 管线出口使用的结构化错误渲染器。
 */
public final class ToolErrorRenderer {
    private ToolErrorRenderer() {
    }

    public static Map<String, Object> render(PixFlowException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("isError", true);
        payload.put("code", error.code().code());
        payload.put("category", error.category().name());
        payload.put("message", Sanitizer.sanitizeMessage(error.getMessage()));
        payload.put("recovery", error.recovery().name());
        if (!error.details().isEmpty()) {
            payload.put("details", error.details());
        }
        if (error.traceId() != null) {
            payload.put("traceId", error.traceId());
        }
        return payload;
    }
}
