package com.pixflow.harness.hooks.payload;

import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.permission.PermissionDecision;
import java.util.Map;

public record ToolUsePayload(
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtime,
        HookEvent phase,
        String toolName,
        String toolCallId,
        Map<String, Object> toolInput,
        PermissionDecision permissionDecision,
        Map<String, Object> resultSummary) implements HookPayload {

    public ToolUsePayload {
        runtime = runtime == null ? RuntimeScope.main() : runtime;
        toolInput = MapCopies.immutableMap(toolInput);
        resultSummary = MapCopies.immutableMap(resultSummary);
    }

    public ToolUsePayload withToolInput(Map<String, Object> newInput) {
        return new ToolUsePayload(
                conversationId,
                turnNo,
                traceId,
                runtime,
                phase,
                toolName,
                toolCallId,
                newInput,
                permissionDecision,
                resultSummary);
    }
}
