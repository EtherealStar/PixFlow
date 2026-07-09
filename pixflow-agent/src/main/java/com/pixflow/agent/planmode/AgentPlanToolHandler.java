package com.pixflow.agent.planmode;

import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code plan} 工具 handler。
 *
 * <p>对应 {@code agent.md §11.2}：
 * handler 调 PlanModeController.enter()，不直接改 RuntimeState。
 */
@Component
public class AgentPlanToolHandler implements ToolHandler {

    private final PlanModeController controller;

    public AgentPlanToolHandler(PlanModeController controller) {
        this.controller = controller;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        try {
            controller.enter(invocation.runtimeContext());
        } catch (IllegalStateException e) {
            return new ToolHandlerOutput("Plan mode transition rejected",
                    Map.of("error", true, "error_code", "plan_mode_transition_rejected"));
        }
        return new ToolHandlerOutput(
                "Entered Plan mode. Read-only tools only; write tools hidden.",
                Map.of("plan_mode", "ACTIVE")
        );
    }
}
