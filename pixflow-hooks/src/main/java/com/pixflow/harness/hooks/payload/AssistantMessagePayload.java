package com.pixflow.harness.hooks.payload;

import java.util.List;
import java.util.Map;

public record AssistantMessagePayload(
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtime,
        String messageId,
        String content,
        List<String> toolCallIds,
        Map<String, Object> metadata) implements HookPayload {

    public AssistantMessagePayload {
        runtime = runtime == null ? RuntimeScope.main() : runtime;
        toolCallIds = MapCopies.immutableStringList(toolCallIds);
        metadata = MapCopies.immutableMap(metadata);
    }
}
