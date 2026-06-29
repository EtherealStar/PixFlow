package com.pixflow.harness.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCallClassification(
        boolean readOnly,
        boolean concurrencySafe,
        String permissionSubjectName,
        Map<String, Object> subjectMetadata,
        ToolResultPolicy resultPolicy) {

    public ToolCallClassification {
        permissionSubjectName = permissionSubjectName == null || permissionSubjectName.isBlank()
                ? "tool"
                : permissionSubjectName;
        subjectMetadata = immutableCopy(subjectMetadata);
        resultPolicy = resultPolicy == null ? ToolResultPolicy.defaults() : resultPolicy;
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
