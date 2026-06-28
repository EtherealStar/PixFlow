package com.pixflow.harness.hooks.payload;

public sealed interface HookPayload
        permits UserPromptSubmitPayload, ToolUsePayload, AssistantMessagePayload,
                TurnStoppedPayload, TaskLifecyclePayload, CompactionPayload {

    String conversationId();

    Integer turnNo();

    String traceId();

    RuntimeScope runtime();
}
