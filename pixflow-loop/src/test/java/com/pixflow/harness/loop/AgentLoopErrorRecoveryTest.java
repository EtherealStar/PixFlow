package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.event.AgentEventType;
import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.error.AiErrorCode;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * AgentLoop 错误恢复路径测试。
 *
 * <ul>
 *   <li>首次 CONTEXT_LIMIT → reactive compact；</li>
 *   <li>不可恢复异常 → TurnTrace.abort + ErrorRecorder.record + 上抛；</li>
 *   <li>{@code continueStream} 不派发 USER_PROMPT_SUBMIT 且不追加 user。</li>
 * </ul>
 */
class AgentLoopErrorRecoveryTest {

    @Test
    void contextLimitFirstTimeTriggersReactiveCompactThenSucceeds() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        AtomicInteger attempts = new AtomicInteger();
        FakeChatModelClient client = new FakeChatModelClient() {
            @Override
            public Flux<ChatStreamEvent> stream(com.pixflow.infra.ai.chat.ChatRequest request) {
                if (attempts.getAndIncrement() == 0) {
                    return Flux.error(new PixFlowException(
                            CommonErrorCode.CONTEXT_LIMIT_EXCEEDED, "context too long"));
                }
                return Flux.just(new ChatStreamEvent.TextDelta("recovered", 0),
                        new ChatStreamEvent.Completed("", List.of(),
                                StopReason.STOP, new com.pixflow.infra.ai.model.TokenUsage(10, 5, 15)));
            }
        };
        AgentLoop loop = newHarness(new RuntimeState(), client, new FakeToolExecutor(),
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder(),
                new com.pixflow.harness.context.store.MessageStore());

        String result = loop.stream("q", List.of(), sink, "sys", List.of());

        assertThat(result).isEqualTo("recovered");
        assertThat(loop.state().hasAttemptedReactiveCompact()).isTrue();
        assertThat(loop.state().lastTransition()).isEqualTo(TransitionReason.COMPLETED);
    }

    @Test
    void unrecoverableExceptionAbortsTurnTraceAndRecordsError() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient() {
            @Override
            public Flux<ChatStreamEvent> stream(com.pixflow.infra.ai.chat.ChatRequest request) {
                return Flux.error(new PixFlowException(CommonErrorCode.INTERNAL_ERROR, "boom"));
            }
        };
        InMemoryTraceRecorder rec = new InMemoryTraceRecorder();
        RecordingErrorRecorder errs = new RecordingErrorRecorder();
        AgentLoop loop = newHarness(new RuntimeState(), client, new FakeToolExecutor(),
                new FakeHookRegistry(), rec, errs, new com.pixflow.harness.context.store.MessageStore());

        assertThatThrownBy(() -> loop.stream("q", List.of(), sink, "sys", List.of()))
                .isInstanceOf(PixFlowException.class);

        assertThat(errs.count()).isEqualTo(1);
        assertThat(rec.traces()).hasSize(1);
        InMemoryTraceRecorder.InMemoryTurnTrace trace = rec.traces().get(0);
        assertThat(trace.aborted()).isTrue();
        assertThat(trace.committed()).isFalse();
        // 不 emit error 事件
        boolean hasErrorEvent = sink.events().stream()
                .anyMatch(e -> e.type().name().equals("ERROR"));
        assertThat(hasErrorEvent).isFalse();
    }

    @Test
    void continueStreamSkipsUserPromptSubmitAndDoesNotAppendUser() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        FakeChatModelClient client = new FakeChatModelClient().enqueueText("ok");
        FakeHookRegistry hooks = new FakeHookRegistry();
        com.pixflow.harness.context.store.MessageStore store = new com.pixflow.harness.context.store.MessageStore();
        store.appendUser("seeded-question");
        store.appendAssistant(com.pixflow.harness.context.model.Message.assistant("seeded-answer"));

        AgentLoop loop = newHarness(new RuntimeState(), client, new FakeToolExecutor(),
                hooks, new InMemoryTraceRecorder(), new RecordingErrorRecorder(),
                store);

        String result = loop.continueStream(sink, "sys", List.of());

        assertThat(result).isEqualTo("ok");
        assertThat(hooks.dispatchedOfType(HookEvent.USER_PROMPT_SUBMIT)).isEmpty();
        // store 应当仍是 user + assistant + 1 条 assistant（continueStream 走的 assistant）
        assertThat(store.currentMessages()).hasSize(3);
        assertThat(hooks.dispatchedOfType(HookEvent.ASSISTANT_MESSAGE_COMPLETED)).hasSize(1);
        assertThat(hooks.dispatchedOfType(HookEvent.TURN_STOPPED)).hasSize(1);
        assertThat(sink.eventsOfType(AgentEventType.COMPLETED)).hasSize(1);
    }

    @Test
    void retryableModelErrorIsNotResubscribedByLoop() {
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        AtomicInteger streamCalls = new AtomicInteger();
        AtomicInteger subscriptions = new AtomicInteger();
        FakeChatModelClient client = new FakeChatModelClient() {
            @Override
            public Flux<ChatStreamEvent> stream(com.pixflow.infra.ai.chat.ChatRequest request) {
                streamCalls.incrementAndGet();
                return Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.error(new PixFlowException(AiErrorCode.MODEL_PROVIDER_ERROR, "temporary provider failure"));
                });
            }
        };

        AgentLoop loop = newHarness(new RuntimeState(), client, new FakeToolExecutor(),
                new FakeHookRegistry(), new InMemoryTraceRecorder(), new RecordingErrorRecorder(),
                new com.pixflow.harness.context.store.MessageStore());

        assertThatThrownBy(() -> loop.stream("q", List.of(), sink, "sys", List.of()))
                .isInstanceOf(PixFlowException.class);
        assertThat(streamCalls).hasValue(1);
        assertThat(subscriptions).hasValue(1);
    }

    // helper —— 测试用工厂：直接构造 AgentLoop 与它依赖的所有 fake
    private static AgentLoop newHarness(RuntimeState state,
                                         FakeChatModelClient client,
                                         FakeToolExecutor toolExec,
                                         FakeHookRegistry hooks,
                                         InMemoryTraceRecorder rec,
                                         RecordingErrorRecorder errs,
                                         com.pixflow.harness.context.store.MessageStore store) {
        state.setConversationId("conv-test");
        com.pixflow.harness.context.budget.ContextBudgetService budgetService = new com.pixflow.harness.context.budget.ContextBudgetService(
                com.pixflow.harness.context.budget.ContextBudgetConfig.defaults(),
                new com.pixflow.harness.context.budget.ConservativeTokenEstimator(), null);
        com.pixflow.harness.context.compaction.ContextCompactionService compactionService = new com.pixflow.harness.context.compaction.ContextCompactionService(
                budgetService, new com.pixflow.harness.context.budget.ConservativeTokenEstimator(), null,
                com.pixflow.harness.context.compaction.CompactionConfig.defaults());
        com.pixflow.harness.context.engine.ContextEngine contextEngine = new com.pixflow.harness.context.engine.ContextEngine(
                store, compactionService, new com.pixflow.harness.context.runtime.CurrentModelContext());
        return new AgentLoop(
                state,
                store,
                contextEngine,
                compactionService,
                client,
                toolExec,
                null, null, null,
                hooks, rec,
                new DefaultPermissionContextFactory(),
                errs,
                new LoopProperties(),
                Executors.newSingleThreadExecutor());
    }
}
