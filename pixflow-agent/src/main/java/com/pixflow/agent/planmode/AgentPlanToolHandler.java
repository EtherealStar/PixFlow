package com.pixflow.agent.planmode;

import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.tools.ToolHandler;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code plan} 工具 handler。
 *
 * <p>对应 {@code agent.md §11.2}：
 * handler 调 PlanModeController.enter()，不直接改 RuntimeState。
 */
@Component
public class AgentPlanToolHandler implements ToolHandler {

    private final PlanModeController controller;
    private final RuntimeState runtimeState;

    public AgentPlanToolHandler(PlanModeController controller, RuntimeState runtimeState) {
        this.controller = controller;
        this.runtimeState = runtimeState;
    }

    @Override
    public ToolHandlerOutput handle(ToolInvocation invocation) {
        try {
            controller.enter(runtimeState);
        } catch (IllegalStateException e) {
            return new ToolHandlerOutput(e.getMessage(), Map.of("error", true));
        }
        return new ToolHandlerOutput(
                "Entered Plan mode. Read-only tools only; write tools hidden.",
                Map.of("plan_mode", "ACTIVE")
        );
    }
}