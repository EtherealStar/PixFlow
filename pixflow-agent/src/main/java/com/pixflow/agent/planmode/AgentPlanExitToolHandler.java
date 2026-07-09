package com.pixflow.agent.planmode;

import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code plan_exit} 工具 handler。
 *
 * <p>对应 {@code agent.md §11.3}：
 * handler 调 PlanModeController.exit(draftPlan)，保留草拟计划到下回合。
 */
@Component
public class AgentPlanExitToolHandler implements ToolHandler {

    private final PlanModeController controller;

    public AgentPlanExitToolHandler(PlanModeController controller) {
        this.controller = controller;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        Map<String, Object> args = invocation.arguments();
        String draftPlan = (String) args.getOrDefault("draftPlan", "");
        try {
            controller.exit(invocation.runtimeContext(), draftPlan);
        } catch (IllegalStateException e) {
            return new ToolHandlerOutput("Plan mode transition rejected",
                    Map.of("error", true, "error_code", "plan_mode_transition_rejected"));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plan_mode", "OFF");
        if (draftPlan != null && !draftPlan.isBlank()) {
            metadata.put("draft_plan", draftPlan);
        }
        return new ToolHandlerOutput("Exited Plan mode. Plan draft saved.", metadata);
    }
}
