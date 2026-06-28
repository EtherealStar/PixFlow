package com.pixflow.harness.hooks.payload;

import java.util.Map;

public record CompactionPayload(
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtime,
        String trigger,
        Integer tokenBefore,
        Integer tokenAfter,
        String failureReason,
        Map<String, Object> metadata) implements HookPayload {

    public CompactionPayload {
        runtime = runtime == null ? RuntimeScope.main() : runtime;
        metadata = MapCopies.immutableMap(metadata);
    }
}
