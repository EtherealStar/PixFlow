package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.event.AgentEventType;
import com.pixflow.harness.loop.stream.ModelStreamConsumer;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ModelStreamConsumerTest {
    @Test
    void lengthStopReasonMarksOutputInterrupted() {
        RuntimeState state = new RuntimeState();
        ModelStreamConsumer consumer = new ModelStreamConsumer();

        var outcome = consumer.consume(Flux.just(
                new ChatStreamEvent.TextDelta("partial", 0),
                new ChatStreamEvent.Completed("partial", List.of(), StopReason.LENGTH, new TokenUsage(1, 2, 3))),
                new RecordingAgentEventSink(),
                state,
                java.util.Map.of("assistantCallId", "a1", "modelTurnIndex", 1));

        assertThat(outcome.stopReason()).isEqualTo(StopReason.LENGTH);
        assertThat(outcome.outputInterrupted()).isTrue();
    }

    @Test
    void attemptResetEmitsRateLimitRetryTransitionMetadata() {
        RuntimeState state = new RuntimeState();
        state.setTraceId("trace-1");
        RecordingAgentEventSink sink = new RecordingAgentEventSink();
        ModelStreamConsumer consumer = new ModelStreamConsumer();

        consumer.consume(Flux.just(
                new ChatStreamEvent.AttemptReset(
                        new PixFlowException(AiErrorCode.MODEL_PROVIDER_ERROR,
                                "provider failed with api_key=secret-value"),
                        2,
                        9),
                new ChatStreamEvent.TextDelta("ok", 0),
                new ChatStreamEvent.Completed("ok", List.of(), StopReason.STOP, new TokenUsage(1, 2, 3))),
                sink,
                state,
                java.util.Map.of("assistantCallId", "a1", "modelTurnIndex", 1));

        var transition = sink.eventsOfType(AgentEventType.TRANSITION).get(0);
        assertThat(transition.payload()).isEqualTo(TransitionReason.RATE_LIMIT_RETRY);
        assertThat(transition.metadata())
                .containsEntry("attempt", 2)
                .containsEntry("retriesRemaining", 9)
                .containsEntry("errorCode", "MODEL_PROVIDER_ERROR")
                .containsEntry("retrying", true)
                .containsEntry("assistantCallId", "a1")
                .containsEntry("modelTurnIndex", 1)
                .containsEntry("traceId", "trace-1");
        assertThat(String.valueOf(transition.metadata().get("message"))).contains("api_key=***");
    }
}
