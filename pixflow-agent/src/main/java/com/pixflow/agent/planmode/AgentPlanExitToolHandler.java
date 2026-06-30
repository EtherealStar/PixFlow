package com.pixflow.agent.planmode;

import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code plan_exit} 工具 handler。
 *
 * <p>对应 {@code agent.md §11.3}：
 * handler 调 PlanModeController.exit(draftPlan)，保留草拟计划到下回合。
 */
@Component
public class AgentPlanExitToolHandler implements ToolHandler {

    private final PlanModeController controller;
    private final RuntimeState runtimeState;

    public AgentPlanExitToolHandler(PlanModeController controller, RuntimeState runtimeState) {
        this.controller = controller;
        this.runtimeState = runtimeState;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        Map<String, Object> args = invocation.arguments();
        String draftPlan = (String) args.getOrDefault("draftPlan", "");
        try {
            controller.exit(runtimeState, draftPlan);
        } catch (IllegalStateException e) {
            return new ToolHandlerOutput(e.getMessage(), Map.of("error", true));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plan_mode", "OFF");
        if (draftPlan != null && !draftPlan.isBlank()) {
            metadata.put("draft_plan", draftPlan);
        }
        return new ToolHandlerOutput("Exited Plan mode. Plan draft saved.", metadata);
    }
}