package com.pixflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.agent.config.AgentProperties;
import com.pixflow.agent.memory.MemoryRecallPlanner;
import com.pixflow.agent.memory.MemoryRecallSignal;
import com.pixflow.agent.planmode.PlanModeController;
import com.pixflow.agent.planmode.PlanModeState;
import com.pixflow.agent.prompt.DynamicPromptAssembler;
import com.pixflow.agent.sessionmemory.SessionMemoryService;
import com.pixflow.common.observability.ErrorRecorder;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.budget.TokenEstimator;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.sessionmemory.SessionMemoryContent;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.permission.PermissionContextFactory;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.tools.ToolExecutor;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemorySection;
import com.pixflow.module.memory.recall.MemoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator 单元测试。
 *
 * <p>聚焦 8 步编排顺序与同步召回触发点；
 * 不测试 {@link AgentOrchestrator#streamNewTurn}（依赖 {@code AgentLoop} 真构造，
 * 走集成测试覆盖）。
 */
class AgentOrchestratorTest {

    @Test
    void recall_triggers_memory_planner_synchronously() {
        // Given
        MemoryRecallPlanner planner = mock(MemoryRecallPlanner.class);
        MemoryContext expected = memoryContext("conv-1", 1,
                new MemorySection("user_preferences", "偏好:\n- key=value",
                        List.of(item("pref-1", MemoryType.PREFERENCE, "key=value")), 10, Map.of()));
        when(planner.plan(any(MemoryRecallSignal.class))).thenReturn(expected);

        AgentOrchestrator orchestrator = buildOrchestrator(planner,
                mock(SessionMemoryService.class), mock(DynamicPromptAssembler.class));

        // When
        MemoryContext actual = orchestrator.recall(newState(), "user prompt",
                null, List.of(), List.of(), List.of());

        // Then
        verify(planner, times(1)).plan(any(MemoryRecallSignal.class));
        assertEquals("conv-1", actual.conversationId());
        assertEquals(1, actual.sections().size());
    }

    @Test
    void prepareTurn_calls_recall_then_session_memory_then_assembler() {
        // Given
        MemoryRecallPlanner planner = mock(MemoryRecallPlanner.class);
        SessionMemoryService sessionMem = mock(SessionMemoryService.class);
        DynamicPromptAssembler assembler = mock(DynamicPromptAssembler.class);
        when(planner.plan(any(MemoryRecallSignal.class))).thenReturn(memoryContext("conv-1", 1));
        when(sessionMem.load("conv-1")).thenReturn(Optional.of(
                new SessionMemoryContent("## Summary\n- past decision", "abc123")));
        when(assembler.assemble(any())).thenReturn("rendered system prompt");

        AgentOrchestrator orchestrator = buildOrchestrator(planner, sessionMem, assembler);

        // When
        RuntimeState state = newState();
        Map<String, Object> result = orchestrator.prepareTurn(
                state, "conv-1", 1, null,
                List.of(), List.of(),
                List.of(), "user prompt", List.of());

        // Then
        verify(planner, times(1)).plan(any(MemoryRecallSignal.class));
        verify(sessionMem, times(1)).load("conv-1");
        verify(assembler, times(1)).assemble(any());
        assertEquals("rendered system prompt", result.get("systemPrompt"));
        assertNotNull(result.get("memoryContext"));
    }

    @Test
    void plan_mode_controller_reads_OFF_for_empty_state() {
        PlanModeController controller = new PlanModeController(new AgentProperties());
        RuntimeState state = newState();
        assertEquals(PlanModeState.OFF, controller.readPlanMode(state));
    }

    @Test
    void plan_mode_controller_transitions_OFF_to_ACTIVE_on_enter() {
        PlanModeController controller = new PlanModeController(new AgentProperties());
        RuntimeState state = newState();
        controller.enter(state);
        assertEquals(PlanModeState.ACTIVE, controller.readPlanMode(state));
    }

    private static RuntimeState newState() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("conv-1");
        state.setTraceId(UUID.randomUUID().toString());
        state.setTurnNo(1);
        return state;
    }

    private static AgentOrchestrator buildOrchestrator(MemoryRecallPlanner planner,
                                                       SessionMemoryService sessionMem,
                                                       DynamicPromptAssembler assembler) {
        return new AgentOrchestrator(
                planner,
                sessionMem,
                assembler,
                mock(ToolRegistry.class),
                new PlanModeController(new AgentProperties()),
                new AgentProperties(),
                mock(LoopProperties.class),
                new ObjectMapper(),
                mock(PermissionPolicy.class),
                mock(ChatModelClient.class),
                mock(ToolExecutor.class),
                mock(HookRegistry.class),
                mock(TraceRecorder.class),
                mock(ErrorRecorder.class),
                mock(PermissionContextFactory.class),
                mock(TokenEstimator.class),
                mock(ContextBudgetService.class),
                mock(ContextCompactionService.class),
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private static MemoryContext memoryContext(String conversationId, int turnNo, MemorySection... sections) {
        return new MemoryContext(conversationId, turnNo, List.of(sections), Map.of("test", true), false);
    }

    private static com.pixflow.module.memory.recall.MemoryItem item(
            String id, MemoryType type, String text) {
        return new com.pixflow.module.memory.recall.MemoryItem(
                id, type, text, "test", "", "", 1.0, 1.0,
                1.0, 1.0, 1.0, null, null, Map.of());
    }
}
