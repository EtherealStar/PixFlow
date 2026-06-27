package com.pixflow.harness.permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限评估结果。
 */
public record PermissionDecision(
        PermissionAction action,
        String reason,
        PermissionSource source,
        String subject,
        Map<String, Object> metadata) {

    public PermissionDecision {
        metadata = immutableCopy(metadata);
    }

    public static PermissionDecision allow(String subject, PermissionSource source) {
        return new PermissionDecision(PermissionAction.ALLOW, "允许执行", source, subject, Map.of());
    }

    public static PermissionDecision deny(String subject, PermissionSource source, String reason) {
        return new PermissionDecision(PermissionAction.DENY, reason, source, subject, Map.of());
    }

    public static PermissionDecision confirmRequired(
            String subject, PermissionSource source, String reason, Map<String, Object> metadata) {
        return new PermissionDecision(PermissionAction.CONFIRM_REQUIRED, reason, source, subject, metadata);
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }
}
