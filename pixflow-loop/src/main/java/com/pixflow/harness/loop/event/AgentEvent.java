package com.pixflow.harness.loop.event;

import com.pixflow.harness.loop.MetadataValues;
import java.util.Map;
import java.util.Objects;

/**
 * loop 事件载体 record。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@link #type}：事件类型，</li>
 *   <li>{@link #text}：{@link AgentEventType#ASSISTANT_DELTA} 的 delta 文本或
 *       {@link AgentEventType#COMPLETED} 的 final text，其他类型可为空，</li>
 *   <li>{@link #payload}：结构化载荷（{@code ToolCall} / {@code ToolExecutionResult} /
 *       {@code TransitionReason} / {@code TracePruneEntry} 等），</li>
 *   <li>{@link #metadata}：附加元信息（traceId / conversationId / subagentType 等）。</li>
 * </ul>
 */
public record AgentEvent(
        AgentEventType type,
        String text,
        Object payload,
        Map<String, Object> metadata) {
    public AgentEvent {
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        metadata = MetadataValues.immutableCopy(metadata);
    }

    public static AgentEvent delta(String text, Map<String, Object> metadata) {
        return new AgentEvent(AgentEventType.ASSISTANT_DELTA, text, null, metadata);
    }

    public static AgentEvent assistantCompleted(String finalText, String messageId, Map<String, Object> metadata) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        return new AgentEvent(AgentEventType.ASSISTANT_MESSAGE_COMPLETED, finalText,
                Map.of("messageId", messageId), metadata);
    }

    public static AgentEvent toolCallReady(Object payload, Map<String, Object> metadata) {
        return new AgentEvent(AgentEventType.TOOL_CALL_READY, null, payload, metadata);
    }

    public static AgentEvent toolStarted(Object payload, Map<String, Object> metadata) {
        return new AgentEvent(AgentEventType.TOOL_STARTED, null, payload, metadata);
    }

    public static AgentEvent toolResult(Object payload, Map<String, Object> metadata) {
        return new AgentEvent(AgentEventType.TOOL_RESULT, null, payload, metadata);
    }

    public static AgentEvent transition(Object payload, Map<String, Object> metadata) {
        return new AgentEvent(AgentEventType.TRANSITION, null, payload, metadata);
    }

    public static AgentEvent completed(String finalText, Map<String, Object> metadata) {
        return new AgentEvent(AgentEventType.COMPLETED, finalText, null, metadata);
    }
}
