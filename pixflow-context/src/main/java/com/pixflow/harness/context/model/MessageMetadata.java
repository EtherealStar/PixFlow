package com.pixflow.harness.context.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MessageMetadata(Map<String, Object> values) {
    public static final String EVENT = "event";

    public static final String EVENT_SKILL_INVOCATION = "skill_invocation";

    public static final String EVENT_PLAN_MODE_CHANGE = "plan_mode_change";

    public static final String TOOL_CALL_IDS = "toolCallIds";

    public static final String ASSISTANT_TOOL_CALLS = "assistantToolCalls";

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

    public static MessageMetadata of(Map<String, Object> values) {
        return new MessageMetadata(values);
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

    public List<AssistantToolCall> assistantToolCalls() {
        Object value = values.get(ASSISTANT_TOOL_CALLS);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<AssistantToolCall> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof AssistantToolCall toolCall) {
                result.add(toolCall);
            } else if (item instanceof Map<?, ?> map) {
                result.add(AssistantToolCall.fromMetadataMap(map));
            } else {
                throw new IllegalArgumentException("assistant tool call metadata item is invalid");
            }
        }
        return List.copyOf(result);
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
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(copyValue(item));
            }
            return List.copyOf(copy);
        }
        if (value instanceof AssistantToolCall toolCall) {
            return toolCall;
        }
        return value;
    }
}
