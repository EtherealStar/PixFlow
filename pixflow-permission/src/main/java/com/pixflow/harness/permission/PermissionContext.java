package com.pixflow.harness.permission;

/**
 * 一次授权评估所需的服务端可信上下文。
 *
 * <p>principal 允许为空，以便策略把“尚未认证”转换成稳定的终态拒绝；其余字段不得
 * 从模型输入或工具参数中拼装。
 */
public record PermissionContext(
        PermissionPrincipal principal,
        PermissionRuntimeScope runtimeScope,
        PermissionPlanMode planMode,
        String conversationId,
        String toolCallId) {

    public PermissionContext {
        conversationId = PermissionValues.requireText(conversationId, "conversationId");
        toolCallId = PermissionValues.requireText(toolCallId, "toolCallId");
    }

    public PermissionContext forToolCall(String currentToolCallId, PermissionPlanMode currentPlanMode) {
        return new PermissionContext(
                principal,
                runtimeScope,
                currentPlanMode,
                conversationId,
                currentToolCallId);
    }
}
