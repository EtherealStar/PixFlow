package com.pixflow.infra.mq.trace;

import com.pixflow.infra.mq.retry.RetryHeaders;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public class MdcTraceHeaderPropagator implements TraceHeaderPropagator {
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    public Map<String, Object> inject(Map<String, Object> headers) {
        Map<String, Object> copy = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        String traceId = MDC.get(MDC_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            copy.put(RetryHeaders.TRACE_ID, traceId);
        }
        return copy;
    }

    @Override
    public TraceScope restore(Map<String, Object> headers) {
        String previous = MDC.get(MDC_TRACE_ID);
        String traceId = readTraceId(headers);
        if (StringUtils.hasText(traceId)) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        return new MdcTraceScope(traceId, previous);
    }

    private String readTraceId(Map<String, Object> headers) {
        if (headers == null) {
            return null;
        }
        Object value = headers.get(RetryHeaders.TRACE_ID);
        return value == null ? null : String.valueOf(value);
    }

    private record MdcTraceScope(String traceId, String previousTraceId) implements TraceScope {
        @Override
        public void close() {
            if (StringUtils.hasText(previousTraceId)) {
                MDC.put(MDC_TRACE_ID, previousTraceId);
            } else {
                MDC.remove(MDC_TRACE_ID);
            }
        }
    }
}
