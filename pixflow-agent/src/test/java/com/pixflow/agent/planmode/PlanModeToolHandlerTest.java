package com.pixflow.agent.planmode;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.tools.ToolHandlerOutput;
import com.pixflow.harness.tools.ToolInvocation;
import com.pixflow.harness.tools.ToolRuntimeContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanModeToolHandlerTest {

    private final PlanModeController controller = new PlanModeController(new AgentProperties());

    @Test
    void planWritesCurrentInvocationRuntimeContext() {
        FakeRuntimeContext runtimeContext = new FakeRuntimeContext();
        AgentPlanToolHandler handler = new AgentPlanToolHandler(controller);

        ToolHandlerOutput output = handler.handle(invocation("plan", Map.of(), runtimeContext));

        assertThat(output.metadata()).containsEntry("plan_mode", "ACTIVE");
        assertThat(runtimeContext.metadata()).containsEntry("planMode", true);
    }

    @Test
    void planExitWritesCurrentInvocationRuntimeContextAndDraft() {
        FakeRuntimeContext runtimeContext = new FakeRuntimeContext();
        runtimeContext.putMetadata("planMode", true);
        AgentPlanExitToolHandler handler = new AgentPlanExitToolHandler(controller);

        ToolHandlerOutput output = handler.handle(invocation(
                "plan_exit",
                Map.of("draftPlan", "draft plan content"),
                runtimeContext));

        assertThat(output.metadata())
                .containsEntry("plan_mode", "OFF")
                .containsEntry("draft_plan", "draft plan content");
        assertThat(runtimeContext.metadata())
                .containsEntry("planMode", false)
                .containsEntry("lastPlanDraft", "draft plan content");
    }

    @Test
    void missingRuntimeContextReturnsToolError() {
        AgentPlanToolHandler handler = new AgentPlanToolHandler(controller);

        ToolHandlerOutput output = handler.handle(new ToolInvocation(
                "tc-plan",
                "plan",
                Map.of(),
                "conv-1",
                1,
                "trace-1",
                RuntimeScope.main(),
                Map.of()));

        assertThat(output.metadata()).containsEntry("error", true);
        assertThat(output.metadata()).containsEntry("error_code", "plan_mode_transition_rejected");
        assertThat(output.content()).contains("Plan mode transition rejected");
    }

    private static ToolInvocation invocation(
            String toolName,
            Map<String, Object> args,
            ToolRuntimeContext runtimeContext) {
        return new ToolInvocation(
                "tc-" + toolName,
                toolName,
                args,
                "conv-1",
                1,
                "trace-1",
                RuntimeScope.main(),
                runtimeContext,
                Map.of());
    }

    private static final class FakeRuntimeContext implements ToolRuntimeContext {
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        @Override
        public Map<String, Object> metadata() {
            return metadata;
        }

        @Override
        public void putMetadata(String key, Object value) {
            metadata.put(key, value);
        }
    }
}
