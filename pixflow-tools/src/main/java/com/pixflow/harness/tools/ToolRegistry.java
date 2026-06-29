package com.pixflow.harness.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ToolRegistry {
    Optional<ToolDescriptor> get(String name);

    List<ToolDescriptor> visibleDescriptors(ToolVisibilityContext context);

    List<Map<String, Object>> toolSchemas(ToolVisibilityContext context);

    List<String> toolPromptSections(ToolVisibilityContext context);
}
