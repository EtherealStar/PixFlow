package com.pixflow.harness.hooks.payload;

import java.util.Map;

public record UserPromptSubmitPayload(
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtime,
        String prompt,
        Map<String, Object> attachments,
        Map<String, Object> metadata) implements HookPayload {

    public UserPromptSubmitPayload {
        runtime = runtime == null ? RuntimeScope.main() : runtime;
        attachments = MapCopies.immutableMap(attachments);
        metadata = MapCopies.immutableMap(metadata);
    }
}
