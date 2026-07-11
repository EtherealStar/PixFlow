package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.concurrent.CancellationReason;
import com.pixflow.common.concurrent.CancellationSource;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.harness.loop.event.AgentEventType;
import com.pixflow.harness.tools.ToolExecutionResult;
import com.pixflow.harness.tools.ToolExecutor;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.ToolCall;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentLoopCancellationTest {
    @Test
    void cancellationBeforeModelSubscriptionStopsWithoutRecordingAnError() {
        RuntimeState state = new RuntimeState();
        FakeChatModelClient model = new FakeChatModelClient();
        AgentLoop loop = LoopTestSupport.builder().state(state).modelClient(model).build();
        CancellationSource source = new CancellationSource();
        source.cancel(CancellationReason.CALLER_ABORTED);

        assertThatThrownBy(() -> loop.stream(
                "q", List.of(), new RecordingAgentEventSink(), "sys", List.of(), source.token()))
                .isInstanceOf(OperationCancelledException.class);
        assertThat(model.callCount()).isZero();
    }

    @Test
    void cancellationDuringModelFluxCancelsTraceWithoutCompletedOrErrorRecord() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch fluxCancelled = new CountDownLatch(1);
        FakeChatModelClient model = new FakeChatModelClient() {
            @Override
            public Flux<ChatStreamEvent> stream(ChatRequest request) {
                return Flux.<ChatStreamEvent>never()
                        .doOnSubscribe(ignored -> subscribed.countDown())
                        .doOnCancel(fluxCancelled::countDown);
            }
        };
        RuntimeState state = new RuntimeState();
        LoopTestSupport.Builder builder = LoopTestSupport.builder().state(state).modelClient(model);
        AgentLoop loop = builder.build();
        CancellationSource source = new CancellationSource();
        RecordingAgentEventSink sink = new RecordingAgentEventSink();

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() ->
                loop.stream("q", List.of(), sink, "sys", List.of(), source.token()));
        assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
        source.cancel(CancellationReason.CLIENT_DISCONNECTED);

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(OperationCancelledException.class);
        assertThat(fluxCancelled.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(builder.errorRecorder.count()).isZero();
        assertThat(builder.traceRecorder.traces()).singleElement()
                .extracting(InMemoryTraceRecorder.InMemoryTurnTrace::cancelled)
                .isEqualTo(true);
        assertThat(sink.eventsOfType(AgentEventType.COMPLETED)).isEmpty();
    }

    @Test
    void cancellationAtToolBoundaryDoesNotReturnToolErrors() {
        CancellationSource source = new CancellationSource();
        AtomicInteger toolCalls = new AtomicInteger();
        ToolExecutor cancellingTool = (calls, context) -> {
            toolCalls.incrementAndGet();
            source.cancel(CancellationReason.CALLER_ABORTED);
            context.cancellation().throwIfCancellationRequested();
            return List.<ToolExecutionResult>of();
        };
        FakeChatModelClient model = new FakeChatModelClient().enqueueToolCalls(
                List.of(new ToolCall("tc1", "search", "{}")), "");
        RuntimeState state = new RuntimeState();
        LoopTestSupport.Builder builder = LoopTestSupport.builder()
                .state(state)
                .modelClient(model)
                .toolExecutor(cancellingTool);
        RecordingAgentEventSink sink = new RecordingAgentEventSink();

        assertThatThrownBy(() -> builder.build().stream(
                "q", List.of(), sink, "sys", List.of(), source.token()))
                .isInstanceOf(OperationCancelledException.class);
        assertThat(toolCalls).hasValue(1);
        assertThat(builder.errorRecorder.count()).isZero();
        assertThat(sink.eventsOfType(AgentEventType.COMPLETED)).isEmpty();
    }
}
