package com.pixflow.module.conversation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventSink;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseAgentEventSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void emit(AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName(event))
                    .data(objectMapper.writeValueAsString(toFrame(event))));
        } catch (IOException ex) {
            throw new IllegalStateException("SSE client disconnected", ex);
        }
    }

    public synchronized void error(Throwable error) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(Map.of(
                            "message", error == null ? "unknown error" : String.valueOf(error.getMessage())))));
        } catch (IOException ignored) {
            // 客户端已断开时无需二次处理。
        } finally {
            emitter.completeWithError(error);
        }
    }

    private static String eventName(AgentEvent event) {
        return event.type().name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> toFrame(AgentEvent event) {
        return Map.of(
                "type", event.type().name(),
                "text", event.text(),
                "payload", event.payload() == null ? Map.of() : event.payload(),
                "metadata", event.metadata());
    }
}
