package com.pixflow.harness.context.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MessageMetadata(Map<String, Object> values) {
    public static final String TOOL_CALL_IDS = "toolCallIds";
    public static final String TOOL_RESULT_EXTERNALIZED = "toolResultExternalized";
    public static final String TOOL_RESULT_REF = "toolResultRef";
    public static final String MICROCOMPACTED = "microcompacted";
    public static final String PLACEHOLDER = "placeholder";
    public static final String COMPACT_BOUNDARY = "isCompactBoundary";
    public static final String COMPACT_SUMMARY = "isCompactSummary";
    public static final String COMPACT_TRIGGER = "compactTrigger";
    public static final String ATTACHMENT_TYPE = "attachmentType";
    public static final String ATTACHMENT_REF = "attachmentRef";
    public static final String ATTACHED_PACKAGE_ID = "attachedPackageId";
    public static final String ATTACHMENT_ID = "attachmentId";

    public MessageMetadata {
        values = immutableCopy(values);
    }

    public static MessageMetadata empty() {
        return new MessageMetadata(Map.of());
    }

    public MessageMetadata with(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put(key, value);
        return new MessageMetadata(next);
    }

    public boolean flag(String key) {
        Object value = values.get(key);
        return value instanceof Boolean bool && bool;
    }

    public List<String> toolCallIds() {
        Object value = values.get(TOOL_CALL_IDS);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), copyValue(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return value;
    }
}
