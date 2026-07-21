package com.pixflow.app.web.conversation.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.tools.ToolCall;
import com.pixflow.harness.tools.ToolExecutionResult;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.app.proposal.ProposalReadyEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseAgentEventSinkTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void hidesToolIdentityAndArgumentsBehindSafeStatus() throws Exception {
        AgentEvent event = AgentEvent.toolCallReady(new ToolCall(
                "call-1", "search_assets", Map.of("query", "secret"), "c1", 1,
                "trace-1", null, Map.of()), Map.of("providerPrompt", "secret"));

        Object payload = SseAgentEventSink.toPayload(event);
        String json = objectMapper.writeValueAsString(payload);

        assertThat(SseAgentEventSink.eventName(event)).isEqualTo("tool_status");
        assertThat(json).isEqualTo("{\"label\":\"正在查找相关信息\",\"state\":\"QUEUED\"}");
        assertThat(json).doesNotContain("toolName", "call-1", "secret", "trace-1");
    }

    @Test
    void projectsToolCompletionWithoutResultContent() throws Exception {
        AgentEvent event = AgentEvent.toolResult(ToolExecutionResult.success(
                "call-2", "read_asset", "objectKey=private", Map.of("provider", "private")), Map.of());

        String json = objectMapper.writeValueAsString(SseAgentEventSink.toPayload(event));

        assertThat(json).isEqualTo("{\"label\":\"正在读取素材\",\"state\":\"SUCCEEDED\"}");
        assertThat(json).doesNotContain("objectKey", "provider", "call-2");
    }

    @Test
    void emitsTypedProposalAfterSafeToolStatus() {
        CountingEmitter emitter = new CountingEmitter();
        SseAgentEventSink sink = new SseAgentEventSink(emitter, objectMapper);
        ProposalReadyEvent proposal = new ProposalReadyEvent(
                "proposal-1", "conversation-1", "IMAGE_PROCESS", "处理素材",
                "方案已准备完成", java.util.List.of("已选择 2 项素材"),
                Instant.parse("2026-07-21T00:00:00Z"));

        sink.emit(AgentEvent.toolResult(ToolExecutionResult.success(
                "call-2", "submit_image_plan", "private tool result",
                Map.of(ProposalReadyEvent.METADATA_KEY, proposal)), Map.of()));

        assertThat(emitter.sendCount).isEqualTo(2);
    }

    @Test
    void projectsAssistantEventsWithOnlyPublicFields() throws Exception {
        AgentEvent completed = AgentEvent.assistantCompleted(
                "final answer", "message-1", Map.of("traceId", "private", "turnNo", 3));

        String json = objectMapper.writeValueAsString(SseAgentEventSink.toPayload(completed));

        assertThat(SseAgentEventSink.eventName(completed)).isEqualTo("assistant_message_completed");
        assertThat(json).isEqualTo("{\"messageId\":\"message-1\",\"finalText\":\"final answer\"}");
    }

    @Test
    void projectsTransitionAsSafeProductLanguage() throws Exception {
        AgentEvent event = AgentEvent.transition(
                TransitionReason.RATE_LIMIT_RETRY,
                Map.of("attempt", 2, "retriesRemaining", 9, "providerPrompt", "secret"));

        String json = objectMapper.writeValueAsString(SseAgentEventSink.toPayload(event));

        assertThat(json).isEqualTo("{\"label\":\"服务繁忙，正在重试\",\"state\":\"RUNNING\"}");
        assertThat(json).doesNotContain("attempt", "retriesRemaining", "providerPrompt");
    }

    @Test
    void rendersTerminalErrorWithStableContractNames() throws Exception {
        PixFlowException error = new PixFlowException(
                AiErrorCode.MODEL_PROVIDER_ERROR,
                "provider failed api_key=secret-value").withTraceId("trace-1");

        String json = objectMapper.writeValueAsString(SseAgentEventSink.errorPayload(error));

        assertThat(json).contains("\"code\":\"MODEL_PROVIDER_ERROR\"")
                .contains("\"traceId\":\"trace-1\"")
                .contains("api_key=***")
                .doesNotContain("errorCode", "secret-value");
    }


    private static final class CountingEmitter
            extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        private int sendCount;

        @Override
        public void send(SseEventBuilder builder) {
            sendCount++;
        }
    }
}
