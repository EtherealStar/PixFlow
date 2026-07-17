package com.pixflow.harness.loop.permission;

import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionRuntimeScope;

/** 只从 RuntimeState 的显式可信字段构造权限上下文。 */
public final class DefaultPermissionContextFactory implements PermissionContextFactory {
    @Override
    public PermissionContext create(RuntimeState state) {
        if (state == null || state.conversationId() == null || state.conversationId().isBlank()) {
            throw new IllegalStateException(
                    "RuntimeState.conversationId must be set before creating PermissionContext");
        }
        String callIdentity = state.traceId() == null || state.traceId().isBlank()
                ? "turn"
                : state.traceId();
        return new PermissionContext(
                state.permissionPrincipal(),
                mapScope(state.runtimeScope()),
                state.permissionPlanMode(),
                state.conversationId(),
                callIdentity);
    }

    private static PermissionRuntimeScope mapScope(RuntimeScope scope) {
        if (scope == null) {
            return PermissionRuntimeScope.INTERNAL;
        }
        if (!scope.subagent()) {
            return PermissionRuntimeScope.MAIN;
        }
        return "explore".equalsIgnoreCase(scope.subagentType())
                ? PermissionRuntimeScope.EXPLORE_CHILD
                : PermissionRuntimeScope.INTERNAL;
    }
}
