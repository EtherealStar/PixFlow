package com.pixflow.app.web.conversation.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.concurrent.CancellationReason;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.app.proposal.ProposalReadyEvent;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.harness.tools.ToolCall;
import com.pixflow.harness.tools.ToolExecutionResult;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 将 Loop 内部事件收敛为浏览器可消费的安全 SSE 白名单。 */
public class SseAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter;

    private final ObjectMapper objectMapper;

    private final Object sendLock;

    private final BooleanSupplier writable;

    private final Consumer<Throwable> transportFailure;

    private final Runnable lateWrite;

    public SseAgentEventSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this(emitter, objectMapper, new Object());
    }

    public SseAgentEventSink(SseEmitter emitter, ObjectMapper objectMapper, Object sendLock) {
        this(emitter, objectMapper, sendLock, () -> true, ignored -> { }, () -> { });
    }

    SseAgentEventSink(
            SseEmitter emitter,
            ObjectMapper objectMapper,
            Object sendLock,
            BooleanSupplier writable,
            Consumer<Throwable> transportFailure) {
        this(emitter, objectMapper, sendLock, writable, transportFailure, () -> { });
    }

    SseAgentEventSink(
            SseEmitter emitter,
            ObjectMapper objectMapper,
            Object sendLock,
            BooleanSupplier writable,
            Consumer<Throwable> transportFailure,
            Runnable lateWrite) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.sendLock = sendLock == null ? new Object() : sendLock;
        this.writable = writable == null ? () -> true : writable;
        this.transportFailure = transportFailure == null ? ignored -> { } : transportFailure;
        this.lateWrite = lateWrite == null ? () -> { } : lateWrite;
    }

    @Override
    public void emit(AgentEvent event) {
        if (!writable.getAsBoolean()) {
            lateWrite.run();
            throw new OperationCancelledException(CancellationReason.CLIENT_DISCONNECTED);
        }
        try {
            synchronized (sendLock) {
                send(eventName(event), toPayload(event));
                ProposalReadyEvent proposal = proposalReady(event);
                if (proposal != null) {
                    send("proposal_ready", proposal);
                }
            }
        } catch (IOException | IllegalStateException ex) {
            transportFailure.accept(ex);
            throw new OperationCancelledException(CancellationReason.CLIENT_DISCONNECTED);
        }
    }

    public boolean sendError(PixFlowException error) {
        if (!writable.getAsBoolean()) {
            lateWrite.run();
            return false;
        }
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(objectMapper.writeValueAsString(errorPayload(error))));
            }
            return true;
        } catch (IOException | IllegalStateException ex) {
            transportFailure.accept(ex);
            return false;
        }
    }

    public boolean sendCompleted(String messageId, boolean stopped) {
        if (!writable.getAsBoolean()) {
            lateWrite.run();
            return false;
        }
        try {
            synchronized (sendLock) {
                send("completed", new CompletedPayload(messageId, stopped));
            }
            return true;
        } catch (IOException | IllegalStateException ex) {
            transportFailure.accept(ex);
            return false;
        }
    }

    private void send(String name, Object payload) throws IOException {
        emitter.send(SseEmitter.event()
                .name(name)
                .data(objectMapper.writeValueAsString(payload)));
    }

    static String eventName(AgentEvent event) {
        return switch (event.type()) {
            case ASSISTANT_DELTA -> "assistant_delta";
            case ASSISTANT_MESSAGE_COMPLETED -> "assistant_message_completed";
            case TOOL_CALL_READY, TOOL_STARTED, TOOL_RESULT -> "tool_status";
            case TRANSITION -> "transition";
            case COMPLETED -> "completed";
        };
    }

    static Object toPayload(AgentEvent event) {
        return switch (event.type()) {
            case ASSISTANT_DELTA -> new AssistantDeltaPayload(event.text());
            case ASSISTANT_MESSAGE_COMPLETED -> new AssistantMessageCompletedPayload(
                    requiredString(event.payload(), "messageId"), event.text());
            case TOOL_CALL_READY -> new ToolStatusPayload(toolLabel(toolName(event.payload())), ToolState.QUEUED);
            case TOOL_STARTED -> new ToolStatusPayload(toolLabel(toolName(event.payload())), ToolState.RUNNING);
            case TOOL_RESULT -> {
                ToolExecutionResult result = requireToolResult(event.payload());
                yield new ToolStatusPayload(
                        toolLabel(result.toolName()), result.error() ? ToolState.FAILED : ToolState.SUCCEEDED);
            }
            case TRANSITION -> transitionPayload(event.payload());
            case COMPLETED -> new CompletedPayload(nullableString(event.metadata().get("messageId")), false);
        };
    }

    static ErrorPayload errorPayload(Throwable error) {
        if (error instanceof PixFlowException pf) {
            return new ErrorPayload(pf.code().code(), safeMessage(pf.getMessage()), nullableString(pf.traceId()));
        }
        return new ErrorPayload(
                CommonErrorCode.INTERNAL_ERROR.code(),
                safeMessage(error == null ? "unknown error" : error.getMessage()),
                null);
    }

    private static ProposalReadyEvent proposalReady(AgentEvent event) {
        if (event.type() != com.pixflow.harness.loop.event.AgentEventType.TOOL_RESULT
                || !(event.payload() instanceof ToolExecutionResult result)) {
            return null;
        }
        Object proposal = result.metadata().get(ProposalReadyEvent.METADATA_KEY);
        return proposal instanceof ProposalReadyEvent ready ? ready : null;
    }

    private static TransitionPayload transitionPayload(Object payload) {
        TransitionReason reason = payload instanceof TransitionReason value ? value : null;
        if (reason == null) {
            return new TransitionPayload("正在继续处理", null);
        }
        return switch (reason) {
            case TOOL_USE -> new TransitionPayload("正在使用工具处理", "RUNNING");
            case COMPLETED -> new TransitionPayload("处理完成", "SUCCEEDED");
            case RATE_LIMIT_RETRY -> new TransitionPayload("服务繁忙，正在重试", "RUNNING");
            case REACTIVE_COMPACT_RETRY -> new TransitionPayload("正在整理上下文后继续", "RUNNING");
            case MAX_OUTPUT_TOKENS_ESCALATE, MAX_OUTPUT_TOKENS_RECOVERY ->
                    new TransitionPayload("正在继续生成回答", "RUNNING");
        };
    }

    private static String toolName(Object payload) {
        if (payload instanceof ToolCall call) {
            return call.toolName();
        }
        if (payload instanceof ToolExecutionResult result) {
            return result.toolName();
        }
        return null;
    }

    private static ToolExecutionResult requireToolResult(Object payload) {
        if (payload instanceof ToolExecutionResult result) {
            return result;
        }
        // 未知内部载荷不向前端透传，统一降级为安全失败状态。
        return ToolExecutionResult.error("", "", "", Map.of());
    }

    private static String toolLabel(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "正在处理";
        }
        String normalized = toolName.toLowerCase(Locale.ROOT);
        if (normalized.contains("search") || normalized.contains("find")) {
            return "正在查找相关信息";
        }
        if (normalized.contains("image") || normalized.contains("vision")) {
            return "正在分析图片";
        }
        if (normalized.contains("file") || normalized.contains("asset")) {
            return "正在读取素材";
        }
        if (normalized.contains("proposal") || normalized.contains("plan")) {
            return "正在准备方案";
        }
        return "正在处理";
    }

    private static String requiredString(Object payload, String key) {
        if (payload instanceof Map<?, ?> map) {
            return nullableString(map.get(key));
        }
        return null;
    }

    private static String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String safeMessage(String raw) {
        String sanitized = Sanitizer.sanitizeMessage(raw);
        return sanitized == null || sanitized.isBlank() ? "unknown error" : sanitized;
    }

    public record AssistantDeltaPayload(String text) {
    }

    public record AssistantMessageCompletedPayload(String messageId, String finalText) {
    }

    public record ToolStatusPayload(String label, ToolState state) {
    }

    public enum ToolState {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransitionPayload(String label, String state) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompletedPayload(String messageId, boolean stopped) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorPayload(String code, String message, String traceId) {
    }
}
