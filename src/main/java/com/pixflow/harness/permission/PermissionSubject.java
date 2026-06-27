package com.pixflow.harness.permission;

import com.pixflow.harness.permission.token.ConfirmationAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 权限评估的最小输入视图。
 */
public record PermissionSubject(
        String toolName,
        boolean readOnly,
        ConfirmationAction confirmationAction,
        String conversationId,
        String packageId,
        String payloadHash,
        int actualCount,
        Map<String, Object> metadata) {

    public PermissionSubject {
        toolName = requireText(toolName, "toolName");
        conversationId = requireText(conversationId, "conversationId");
        packageId = requireText(packageId, "packageId");
        payloadHash = requireText(payloadHash, "payloadHash");
        if (actualCount < 0) {
            throw new IllegalArgumentException("actualCount 不能小于 0");
        }
        metadata = immutableCopy(metadata);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
