package com.pixflow.harness.permission;

public interface PermissionPolicy {
    PermissionDecision evaluate(PermissionContext context, PermissionSubject subject);

    boolean isToolVisible(String toolName, boolean readOnly, PermissionContext context);
}
