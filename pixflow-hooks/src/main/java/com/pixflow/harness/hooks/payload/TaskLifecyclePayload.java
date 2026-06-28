package com.pixflow.harness.hooks.payload;

import java.util.Map;

public record TaskLifecyclePayload(
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtime,
        String taskId,
        String status,
        Map<String, Object> taskSummary,
        Map<String, Object> metadata) implements HookPayload {

    public TaskLifecyclePayload {
        runtime = runtime == null ? RuntimeScope.main() : runtime;
        taskSummary = MapCopies.immutableMap(taskSummary);
        metadata = MapCopies.immutableMap(metadata);
    }
}
