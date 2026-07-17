package com.pixflow.harness.permission;

/** 已认证主体在 Permission 边界内的最小不可变投影。 */
public record PermissionPrincipal(String userId, String username) {
    public PermissionPrincipal {
        userId = PermissionValues.requireText(userId, "userId");
        username = PermissionValues.requireText(username, "username");
    }
}
