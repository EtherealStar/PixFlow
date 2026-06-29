package com.pixflow.harness.tools;

import java.util.Map;

@FunctionalInterface
public interface ToolInputValidator {
    void validate(ToolDescriptor descriptor, Map<String, Object> arguments);

    static ToolInputValidator noop() {
        return (descriptor, arguments) -> {
        };
    }
}
