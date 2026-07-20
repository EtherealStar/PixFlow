package com.pixflow.app.web.conversation.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.harness.tools.ToolCall;
import com.pixflow.harness.tools.ToolExecutionResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseAgentEventSinkTest {
    @SuppressWarnings("unchecked")
    @Test
    void projectsRecordToolCallPayload() {
        AgentEvent event = AgentEvent.toolCallReady(new ToolCall(
                "call-1",
                "search",
                Map.of("query", "sku"),
                "c1",
                1,
                "trace-1",
                null,
                Map.of()),
                Map.of("assistantCallId", "a1", "modelTurnIndex", 1, "iteration", 1, "traceId", "trace-1", "turnNo", 7));

        Map<String, Object> payload = SseAgentEventSink.toPayload(event);

        assertThat(SseAgentEventSink.eventName(event)).isEqualTo("tool_call_ready");
        assertThat(payload).containsEntry("toolName", "search");
        assertThat(payload).containsEntry("toolCallId", "call-1");
        assertThat(payload).containsEntry("assistantCallId", "a1");
        assertThat(payload).containsEntry("modelTurnIndex", 1);
        assertThat(payload).containsEntry("traceId", "trace-1");
        assertThat(payload).containsEntry("turnNo", 7);
        assertThat((Map<String, Object>) payload.get("toolInput")).containsEntry("query", "sku");
    }

    @Test
    void projectsRecordToolResultPayloadAndMetadataExternalized() {
        AgentEvent event = AgentEvent.toolResult(ToolExecutionResult.success(
                "call-2",
                "read",
                "ok",
                Map.of("externalized", true)),
                Map.of("assistantCallId", "a1", "modelTurnIndex", 1, "traceId", "trace-1", "turnNo", 7));

        Map<String, Object> payload = SseAgentEventSink.toPayload(event);

        assertThat(SseAgentEventSink.eventName(event)).isEqualTo("tool_result");
        assertThat(payload).containsEntry("toolCallId", "call-2");
        assertThat(payload).containsEntry("toolName", "read");
        assertThat(payload).containsEntry("content", "ok");
        assertThat(payload).containsEntry("externalized", true);
        assertThat(payload).containsEntry("error", false);
        assertThat(payload).containsEntry("assistantCallId", "a1");
        assertThat(payload).containsEntry("modelTurnIndex", 1);
    }

    @Test
    void projectsStringAssistantCompletedFinalText() {
        AgentEvent event = AgentEvent.assistantCompleted("final answer", "ignored payload", Map.of("traceId", "t1", "turnNo", 3));

        Map<String, Object> payload = SseAgentEventSink.toPayload(event);

        assertThat(SseAgentEventSink.eventName(event)).isEqualTo("assistant_message_completed");
        assertThat(payload).containsEntry("finalText", "final answer");
        assertThat(payload).containsEntry("traceId", "t1");
        assertThat(payload).containsEntry("turnNo", 3);
    }

    @Test
    void projectsAssistantDeltaAndCompletedAttribution() {
        AgentEvent delta = AgentEvent.delta("你", Map.of(
                "assistantCallId", "a1",
                "modelTurnIndex", 1,
                "iteration", 1,
                "traceId", "t1",
                "turnNo", 3));
        AgentEvent completed = AgentEvent.completed("你好", Map.of(
                "assistantCallId", "a1",
                "modelTurnIndex", 1,
                "iteration", 1,
                "traceId", "t1",
                "turnNo", 3));

        Map<String, Object> deltaPayload = SseAgentEventSink.toPayload(delta);
        Map<String, Object> completedPayload = SseAgentEventSink.toPayload(completed);

        assertThat(deltaPayload)
                .containsEntry("text", "你")
                .containsEntry("assistantCallId", "a1")
                .containsEntry("modelTurnIndex", 1);
        assertThat(completedPayload)
                .containsEntry("finalText", "你好")
                .containsEntry("assistantCallId", "a1")
                .containsEntry("modelTurnIndex", 1)
                .containsEntry("traceId", "t1")
                .containsEntry("turnNo", 3);
    }

    @Test
    void projectsRetryTransitionMetadata() {
        AgentEvent event = AgentEvent.transition(
                com.pixflow.harness.loop.TransitionReason.RATE_LIMIT_RETRY,
                Map.of(
                        "assistantCallId", "a1",
                        "modelTurnIndex", 1,
                        "traceId", "trace-1",
                        "turnNo", 7,
                        "attempt", 2,
                        "retriesRemaining", 9,
                        "errorCode", "MODEL_PROVIDER_ERROR",
                        "message", "model stream interrupted",
                        "retrying", true));

        Map<String, Object> payload = SseAgentEventSink.toPayload(event);

        assertThat(SseAgentEventSink.eventName(event)).isEqualTo("transition");
        assertThat(payload)
                .containsEntry("reason", "RATE_LIMIT_RETRY")
                .containsEntry("attempt", 2)
                .containsEntry("retriesRemaining", 9)
                .containsEntry("errorCode", "MODEL_PROVIDER_ERROR")
                .containsEntry("message", "model stream interrupted")
                .containsEntry("retrying", true)
                .containsEntry("assistantCallId", "a1")
                .containsEntry("modelTurnIndex", 1)
                .containsEntry("traceId", "trace-1")
                .containsEntry("turnNo", 7);
    }

    @Test
    void rendersTerminalErrorPayloadWithErrorCodeAndSafeMessage() {
        PixFlowException error = new PixFlowException(
                AiErrorCode.MODEL_PROVIDER_ERROR,
                "provider failed api_key=secret-value").withTraceId("trace-1");

        Map<String, Object> payload = SseAgentEventSink.errorPayload(error);

        assertThat(payload)
                .containsEntry("errorCode", "MODEL_PROVIDER_ERROR")
                .containsEntry("traceId", "trace-1");
        assertThat(String.valueOf(payload.get("message"))).contains("api_key=***");
    }
}
