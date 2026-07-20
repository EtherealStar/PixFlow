package com.pixflow.agent.memory;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.module.memory.MemoryService;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextRequest;
import com.pixflow.module.memory.context.MemoryReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRecallPlannerTest {

    @Test
    void plan_delegates_to_module_memory_prepare_context() {
        MemoryService memoryService = mock(MemoryService.class);
        AgentProperties props = new AgentProperties();
        props.getMemory().getRecall().setMaxTokens(1234);
        MemoryContext expected = new MemoryContext("conv-1", 7, List.of(), Map.of(), false);
        when(memoryService.prepareContext(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        MemoryRecallPlanner planner = new MemoryRecallPlanner(memoryService, props);
        MemoryRecallSignal signal = new MemoryRecallSignal(
                "conv-1",
                7,
                "trace-1",
                "帮我看 SKU-A 的投放表现",
                List.of(new MemoryReference("asset:image:1:2", "美妆/a.png")),
                List.of("美妆"),
                Map.of("recent_assistant_messages", List.of("上一轮结论")),
                1234);

        MemoryContext actual = planner.plan(signal);

        ArgumentCaptor<MemoryContextRequest> captor = ArgumentCaptor.forClass(MemoryContextRequest.class);
        verify(memoryService).prepareContext(captor.capture());
        MemoryContextRequest request = captor.getValue();
        assertEquals(expected, actual);
        assertEquals("conv-1", request.conversationId());
        assertEquals(7, request.turnNo());
        assertEquals("trace-1", request.traceId());
        assertEquals("帮我看 SKU-A 的投放表现", request.userPrompt());
        assertEquals(List.of("美妆"), request.categoryHints());
        assertEquals(List.of(new MemoryReference("asset:image:1:2", "美妆/a.png")), request.references());
        assertEquals(1234, request.tokenBudget());
    }
}
