package com.pixflow.harness.permission;

/** 决策阶段的低基数来源，供 trace 使用。 */
public enum PermissionSource {
    AUTHENTICATION,
    ADMINISTRATOR,
    RUNTIME_SCOPE,
    PLAN_MODE,
    CONVERSATION,
    ASSET,
    PROPOSAL,
    TASK,
    POLICY_ALLOW
}
