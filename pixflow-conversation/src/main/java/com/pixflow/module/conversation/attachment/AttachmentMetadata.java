package com.pixflow.module.conversation.attachment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AttachmentMetadata {
    private AttachmentMetadata() {
    }

    static Map<String, Object> normalize(Map<String, ?> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("metadata key must not be blank");
            }
            Object value = normalizeValue(entry.getValue());
            if (value != null) {
                normalized.put(key.trim(), value);
            }
        }
        return Map.copyOf(normalized);
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value) {
        if (value == null) {
            // 素材包字段允许为空；边界处统一删除 null，避免 Map.copyOf 抛裸 NPE。
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object rawKey = entry.getKey();
                if (!(rawKey instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException("metadata nested key must be non-blank string");
                }
                Object nestedValue = normalizeValue(entry.getValue());
                if (nestedValue != null) {
                    nested.put(key.trim(), nestedValue);
                }
            }
            return Map.copyOf(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                Object itemValue = normalizeValue(item);
                if (itemValue != null) {
                    normalized.add(itemValue);
                }
            }
            return List.copyOf(normalized);
        }
        if (value.getClass().isArray()) {
            throw new IllegalArgumentException("metadata array values are not supported");
        }
        throw new IllegalArgumentException("metadata value type is not supported: " + value.getClass().getName());
    }
}
