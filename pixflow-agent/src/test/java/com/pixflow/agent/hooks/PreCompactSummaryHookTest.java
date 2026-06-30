package com.pixflow.agent.hooks;

import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.CompactionPayload;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreCompactSummaryHookTest {

    @Test
    void handle_PRE_COMPACT_returns_metadata_with_instructions() {
        PreCompactSummaryHook hook = new PreCompactSummaryHook();
        CompactionPayload payload = new CompactionPayload(
                "conv-1", 1, "trace-1", RuntimeScope.main(),
                "AUTO", 5000, 6000, null, Map.of()
        );
        HookResult result = hook.handle(HookEvent.PRE_COMPACT, payload);
        assertNotNull(result.metadata());
        assertTrue(result.metadata().containsKey("compact.summaryInstructions"));
        String instructions = (String) result.metadata().get("compact.summaryInstructions");
        assertTrue(instructions.contains("SKU"));
    }

    @Test
    void handle_other_event_returns_noop() {
        PreCompactSummaryHook hook = new PreCompactSummaryHook();
        CompactionPayload payload = new CompactionPayload(
                "conv-1", 1, "trace-1", RuntimeScope.main(),
                "AUTO", 5000, 6000, null, Map.of()
        );
        HookResult result = hook.handle(HookEvent.TURN_STOPPED, payload);
        assertFalse(result.blocked());
    }
}