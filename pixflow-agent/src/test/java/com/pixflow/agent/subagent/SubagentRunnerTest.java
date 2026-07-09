package com.pixflow.agent.subagent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentRunnerTest {

    @Test
    void runAsync_returns_future_immediately() {
        SubagentRunner runner = new SubagentRunner(
                Executors.newFixedThreadPool(2));
        SubagentRequest req = SubagentRequest.explore("conv-1", "tc-1", "explore topic X");
        CompletableFuture<SubagentResult> future = runner.runAsync(req);
        assertNotNull(future);
        // Future is not done immediately (it's async on a different thread)
        // but should complete within a few seconds
        try {
            SubagentResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.isError());
            assertEquals("subagent_runtime_unavailable", result.errorMessage());
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Future did not complete: " + e.getMessage(), e);
        }
    }

    @Test
    void runAsync_for_vision_includes_type_in_result() {
        SubagentRunner runner = new SubagentRunner(
                Executors.newFixedThreadPool(2));
        SubagentRequest req = SubagentRequest.vision(
                "conv-1", "tc-1",
                java.util.List.of("img-1", "img-2"),
                "describe this image"
        );
        SubagentResult result = runner.runAsync(req).join();
        assertTrue(result.isError());
        assertEquals("subagent_runtime_unavailable", result.errorMessage());
        assertEquals(0, result.toolResultCount());
    }
}
