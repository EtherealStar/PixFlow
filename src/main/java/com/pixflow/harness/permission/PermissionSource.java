package com.pixflow.harness.permission;

/**
 * 决策来源，便于审计和 trace 记录。
 */
public enum PermissionSource {
    SUBAGENT_CONSTRAINT,
    TOOL_DENIED,
    TOOL_DISABLED,
    TOKEN_GATE,
    DEFAULT_ALLOW
}
