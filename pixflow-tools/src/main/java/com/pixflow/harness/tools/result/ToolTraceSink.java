package com.pixflow.harness.tools.result;

import java.util.Map;

public interface ToolTraceSink {
    void record(ToolTraceEvent event);

    record ToolTraceEvent(
            String toolName,
            String toolCallId,
            long startedAtMillis,
            long finishedAtMillis,
            boolean error,
            String errorCategory,
            boolean rewritten,
            boolean resultExternalized,
            Map<String, Object> metadata) {
    }
}
