package com.pixflow.harness.tools;

import java.util.Map;

@FunctionalInterface
public interface ToolClassifier {
    ToolCallClassification classify(ToolDescriptor descriptor, Map<String, Object> arguments);

    static ToolClassifier defaultClassifier() {
        return (descriptor, arguments) -> new ToolCallClassification(
                descriptor.readOnlyHint(),
                descriptor.readOnlyHint(),
                descriptor.name(),
                Map.of(),
                descriptor.resultPolicy());
    }
}
