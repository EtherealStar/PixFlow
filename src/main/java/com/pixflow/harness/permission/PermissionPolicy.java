package com.pixflow.harness.permission;

public interface PermissionPolicy {
    PermissionDecision evaluate(PermissionSubject subject, PermissionContext context);

    boolean isToolVisible(String toolName, PermissionContext context);
}
