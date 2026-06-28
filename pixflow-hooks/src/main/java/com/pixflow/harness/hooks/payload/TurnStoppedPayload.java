package com.pixflow.harness.hooks.payload;

import java.util.Map;

public record TurnStoppedPayload(
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtime,
        String reason,
        Map<String, Object> metadata) implements HookPayload {

    public TurnStoppedPayload {
        runtime = runtime == null ? RuntimeScope.main() : runtime;
        metadata = MapCopies.immutableMap(metadata);
    }
}
