package com.pixflow.harness.loop;

import com.pixflow.common.concurrent.CancellationToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.compaction.CompactionConfig;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.engine.ContextEngine;
import com.pixflow.harness.context.runtime.CurrentModelContext;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.loop.event.AgentEventType;
import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ToolCall;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * AgentLoop 续轮判定测试：覆盖
 * <ul>
 *   <li>无工具调用 → COMPLETED + commit + emit COMPLETED；</li>
 *   <li>带工具调用 → 工具执行 + continue；</li>
 *   <li>无 maxTurns：连续 N 轮工具调用后自然结束。</li>
 * </ul>
 */
class AgentLoopContinuationDecisionTest {

    @Test
    void completesWhenModelReturnsNoToolCalls() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient()
                .enqueueText("hello back");

        AgentLoop loop = newHarness(new RuntimeState(), client, new FakeToolExecutor(),
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder());
        String result = loop.stream("hi", List.of(), sink, "system-prompt", List.of(), CancellationToken.NONE);

        assertThat(result).isEqualTo("hello back");
        assertThat(sink.eventsOfType(AgentEventType.COMPLETED)).hasSize(1);
        // trace 应当 commit
        InMemoryTraceRecorder rec = (InMemoryTraceRecorder) field(loop, "traceRecorder");
        assertThat(rec.traces()).hasSize(1);
        assertThat(rec.traces().get(0).committed()).isTrue();
        assertThat(rec.traces().get(0).aborted()).isFalse();
        RecordingErrorRecorder errs = (RecordingErrorRecorder) field(loop, "errorRecorder");
        assertThat(errs.count()).isZero();
    }

    @Test
    void executesToolCallsWhenModelRequestsToolAndCompletesOnNextRound() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient()
                .enqueueToolCalls(List.of(new ToolCall("tc1", "search", "{}")), "Looking...")
                .enqueueText("search result processed");
        FakeToolExecutor toolExec = new FakeToolExecutor();

        AgentLoop loop = newHarness(new RuntimeState(), client, toolExec,
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder());
        String result = loop.stream("q", List.of(), sink, "sys", List.of(), CancellationToken.NONE);

        assertThat(result).isEqualTo("search result processed");
        assertThat(toolExec.totalCalls()).isEqualTo(1);
        assertThat(sink.eventsOfType(AgentEventType.TOOL_CALL_READY)).hasSize(1);
        assertThat(sink.eventsOfType(AgentEventType.TOOL_RESULT)).hasSize(1);
        assertThat(sink.eventsOfType(AgentEventType.COMPLETED)).hasSize(1);
        assertThat(sink.eventsOfType(AgentEventType.ASSISTANT_DELTA).get(0).metadata())
                .containsEntry("assistantCallId", loop.state().traceId() + ":assistant:1")
                .containsEntry("modelTurnIndex", 1);
        assertThat(sink.eventsOfType(AgentEventType.TOOL_CALL_READY).get(0).metadata())
                .containsEntry("assistantCallId", loop.state().traceId() + ":assistant:1")
                .containsEntry("modelTurnIndex", 1);
        assertThat(sink.eventsOfType(AgentEventType.TOOL_RESULT).get(0).metadata())
                .containsEntry("assistantCallId", loop.state().traceId() + ":assistant:1")
                .containsEntry("modelTurnIndex", 1);
        assertThat(sink.eventsOfType(AgentEventType.COMPLETED).get(0).metadata())
                .containsEntry("assistantCallId", loop.state().traceId() + ":assistant:2")
                .containsEntry("modelTurnIndex", 2);
        ChatRequestShapeAssert.assertContainsToolProtocol(client.seenRequests().get(1).messages());
        long toolUse = sink.eventsOfType(AgentEventType.TRANSITION).stream()
                .filter(e -> e.payload() == TransitionReason.TOOL_USE).count();
        long completed = sink.eventsOfType(AgentEventType.TRANSITION).stream()
                .filter(e -> e.payload() == TransitionReason.COMPLETED).count();
        assertThat(toolUse).isEqualTo(1);
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void noMaxTurnsLimitIteratesUntilModelStopsCallingTools() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient()
                .enqueueToolCalls(List.of(new ToolCall("a", "t", "{}")), "iter1")
                .enqueueToolCalls(List.of(new ToolCall("b", "t", "{}")), "iter2")
                .enqueueToolCalls(List.of(new ToolCall("c", "t", "{}")), "iter3")
                .enqueueText("done");
        FakeToolExecutor toolExec = new FakeToolExecutor();

        AgentLoop loop = newHarness(new RuntimeState(), client, toolExec,
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder());
        String result = loop.stream("q", List.of(), sink, "sys", List.of(), CancellationToken.NONE);

        assertThat(result).isEqualTo("done");
        assertThat(toolExec.totalCalls()).isEqualTo(3);
        // 第一轮 tool use，第二轮 tool use，第三轮 tool use，第四轮 COMPLETED
        assertThat(loop.state().iterationCount()).isEqualTo(4);
        assertThat(loop.state().lastTransition()).isEqualTo(TransitionReason.COMPLETED);
    }

    @Test
    void malformedToolArgumentsReturnToolErrorWithoutCallingExecutor() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient()
                .enqueueToolCalls(List.of(new ToolCall("bad", "search", "{not-json")), "Looking...")
                .enqueueText("recovered");
        FakeToolExecutor toolExec = new FakeToolExecutor();

        AgentLoop loop = newHarness(new RuntimeState(), client, toolExec,
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder());
        String result = loop.stream("q", List.of(), sink, "sys", List.of(), CancellationToken.NONE);

        assertThat(result).isEqualTo("recovered");
        assertThat(toolExec.totalCalls()).isZero();
        assertThat(sink.eventsOfType(AgentEventType.TOOL_RESULT)).hasSize(1);
        Object payload = sink.eventsOfType(AgentEventType.TOOL_RESULT).get(0).payload();
        assertThat(payload).isInstanceOf(com.pixflow.harness.tools.ToolExecutionResult.class);
        var toolResult = (com.pixflow.harness.tools.ToolExecutionResult) payload;
        assertThat(toolResult.error()).isTrue();
        assertThat(toolResult.content()).contains("invalid_tool_input");
        assertThat(toolResult.metadata()).containsEntry("errorCategory", "VALIDATION");
        assertThat(toolResult.metadata()).doesNotContainKey("raw");
    }

    @Test
    void missingExecutorResultIsReturnedAsInternalToolError() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient()
                .enqueueToolCalls(List.of(
                        new ToolCall("ok", "search", "{}"),
                        new ToolCall("missing", "read", "{}")), "Looking...")
                .enqueueText("recovered");
        FakeToolExecutor toolExec = new FakeToolExecutor().omitResult("missing");

        AgentLoop loop = newHarness(new RuntimeState(), client, toolExec,
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder());
        loop.stream("q", List.of(), sink, "sys", List.of(), CancellationToken.NONE);

        List<Object> payloads = sink.eventsOfType(AgentEventType.TOOL_RESULT).stream()
                .map(e -> e.payload())
                .toList();
        assertThat(payloads).hasSize(2);
        var missing = (com.pixflow.harness.tools.ToolExecutionResult) payloads.get(1);
        assertThat(missing.toolCallId()).isEqualTo("missing");
        assertThat(missing.error()).isTrue();
        assertThat(missing.content()).contains("tool_execution_missing_result");
        assertThat(missing.metadata()).containsEntry("errorCategory", "INTERNAL");
        assertThat(missing.metadata()).containsEntry("missingToolResult", true);
    }

    private static final class ChatRequestShapeAssert {
        private static void assertContainsToolProtocol(List<ChatMessage> messages) {
            ChatMessage assistant = messages.stream()
                    .filter(m -> m.role() == ChatMessage.Role.ASSISTANT)
                    .findFirst()
                    .orElseThrow();
            assertThat(assistant.parts()).anyMatch(ChatMessage.ToolCallPart.class::isInstance);
            ChatMessage tool = messages.stream()
                    .filter(m -> m.role() == ChatMessage.Role.TOOL)
                    .findFirst()
                    .orElseThrow();
            assertThat(tool.parts()).singleElement().isInstanceOf(ChatMessage.ToolResultPart.class);
        }
    }

    // helper —— 测试用工厂：直接构造 AgentLoop 与它依赖的所有 fake
    private static AgentLoop newHarness(RuntimeState state,
                                         FakeChatModelClient client,
                                         FakeToolExecutor toolExec,
                                         FakeHookRegistry hooks,
                                         InMemoryTraceRecorder rec,
                                         RecordingErrorRecorder errs) {
        state.setConversationId("conv-test");
        MessageStore store = new MessageStore();
        ContextBudgetService budgetService = new ContextBudgetService(
                ContextBudgetConfig.defaults(), new ConservativeTokenEstimator(), null);
        ContextCompactionService compactionService = new ContextCompactionService(
                budgetService, new ConservativeTokenEstimator(), null, CompactionConfig.defaults());
        ContextEngine contextEngine = new ContextEngine(store, compactionService, new CurrentModelContext());
        return new AgentLoop(
                state,
                store,
                contextEngine,
                compactionService,
                client,
                toolExec,
                null,                       // permissionPolicy
                null,                       // resultStorage
                null,                       // planModeView
                hooks,
                rec,
                new DefaultPermissionContextFactory(),
                errs,
                new com.pixflow.harness.loop.config.LoopProperties(),
                Executors.newSingleThreadExecutor());
    }

    private static Object field(Object instance, String name) {
        try {
            java.lang.reflect.Field f = instance.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(instance);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
