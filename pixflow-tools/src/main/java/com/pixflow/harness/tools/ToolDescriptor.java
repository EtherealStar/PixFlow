package com.pixflow.harness.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String prompt,
        boolean readOnlyHint,
        ToolHandler handler,
        ToolClassifier classifier,
        ToolInputValidator validator,
        ToolResultPolicy resultPolicy) {

    public ToolDescriptor {
        name = requireText(name, "name");
        description = requireText(description, "description");
        prompt = prompt == null ? "" : prompt;
        inputSchema = immutableCopy(inputSchema);
        outputSchema = immutableCopy(outputSchema);
        handler = Objects.requireNonNull(handler, "handler");
        classifier = classifier == null ? ToolClassifier.defaultClassifier() : classifier;
        validator = validator == null ? ToolInputValidator.noop() : validator;
        resultPolicy = resultPolicy == null ? ToolResultPolicy.defaults() : resultPolicy;
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
