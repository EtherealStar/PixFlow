package com.pixflow.common.error.render;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE / WS 出口使用的错误帧渲染器。
 */
public final class StreamErrorRenderer {
    private StreamErrorRenderer() {
    }

    public static Map<String, Object> render(PixFlowException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "error");
        payload.put("code", error.code().code());
        payload.put("message", Sanitizer.sanitizeMessage(error.getMessage()));
        if (error.traceId() != null) {
            payload.put("traceId", error.traceId());
        }
        return payload;
    }
}
