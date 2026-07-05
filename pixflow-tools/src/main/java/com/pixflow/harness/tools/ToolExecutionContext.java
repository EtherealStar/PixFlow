package com.pixflow.harness.tools;

import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public record ToolExecutionContext(
        com.pixflow.harness.permission.PermissionPolicy permissionPolicy,
        com.pixflow.harness.permission.PermissionContext permissionContext,
        HookRegistry hookRegistry,
        ToolResultStorage resultStorage,
        ToolTraceSink traceSink,
        PlanModeView planModeView,
        ExecutorService executor,
        Set<String> hiddenTools) {

    public ToolExecutionContext {
        hiddenTools = immutableSet(hiddenTools);
    }

    public ToolVisibilityContext visibilityContext() {
        boolean planMode = planModeView != null && planModeView.isPlanMode();
        return new ToolVisibilityContext(permissionContext, planMode, hiddenTools);
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
