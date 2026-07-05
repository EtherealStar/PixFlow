package com.pixflow.harness.tools;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record ToolVisibilityContext(
        com.pixflow.harness.permission.PermissionContext permissionContext,
        boolean planMode,
        Set<String> hiddenTools) {

    public ToolVisibilityContext {
        hiddenTools = immutableSet(hiddenTools);
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
