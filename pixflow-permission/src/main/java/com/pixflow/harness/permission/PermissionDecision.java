package com.pixflow.harness.permission;

import java.util.Map;
import java.util.Objects;

/** 不携带身份、路径或载荷的安全授权结果。 */
public record PermissionDecision(
        PermissionAction action,
        PermissionErrorCode errorCode,
        PermissionSource source,
        String subject,
        Map<String, Object> metadata) {

    public PermissionDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(source, "source");
        subject = PermissionValues.requireText(subject, "subject");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (action == PermissionAction.ALLOW && errorCode != null) {
            throw new IllegalArgumentException("ALLOW 不得携带错误码");
        }
        if (action == PermissionAction.DENY && errorCode == null) {
            throw new IllegalArgumentException("DENY 必须携带错误码");
        }
    }

    public static PermissionDecision allow(String subject) {
        return new PermissionDecision(
                PermissionAction.ALLOW, null, PermissionSource.POLICY_ALLOW, subject, Map.of());
    }

    public static PermissionDecision deny(
            String subject, PermissionSource source, PermissionErrorCode errorCode) {
        return new PermissionDecision(PermissionAction.DENY, errorCode, source, subject, Map.of());
    }

    /** 对外只暴露稳定、安全的通用原因，详细证明失败不会进入模型上下文。 */
    public String reason() {
        return action == PermissionAction.ALLOW ? "allowed" : "permission denied";
    }
}
