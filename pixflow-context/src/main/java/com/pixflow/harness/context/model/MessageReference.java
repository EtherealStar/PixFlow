package com.pixflow.harness.context.model;

import java.util.Objects;

/**
 * 用户消息中有序素材引用的不可变快照。
 *
 * <p>Context 只校验字符串的安全形状，不解析 canonical Asset Reference 语法；
 * 资源身份、存在性和权限由 Conversation 与 File 边界负责。
 */
public record MessageReference(String referenceKey, String displayPathSnapshot) {
    public MessageReference {
        referenceKey = requireSafeText(referenceKey, "referenceKey");
        displayPathSnapshot = requireSafeText(displayPathSnapshot, "displayPathSnapshot");
    }

    private static String requireSafeText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(fieldName + " must not contain control characters");
            }
        }
        return value;
    }
}
