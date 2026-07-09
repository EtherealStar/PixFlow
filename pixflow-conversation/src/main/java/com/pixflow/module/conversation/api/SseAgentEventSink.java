package com.pixflow.module.conversation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventSink;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseAgentEventSink implements AgentEventSink {
    private static final ConcurrentHashMap<Class<?>, Map<String, Method>> ACCESSOR_CACHE = new ConcurrentHashMap<>();

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final Object sendLock;

    public SseAgentEventSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this(emitter, objectMapper, new Object());
    }

    public SseAgentEventSink(SseEmitter emitter, ObjectMapper objectMapper, Object sendLock) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.sendLock = sendLock == null ? new Object() : sendLock;
    }

    @Override
    public void emit(AgentEvent event) {
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event()
                        .name(eventName(event))
                        .data(objectMapper.writeValueAsString(toPayload(event))));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("SSE client disconnected", ex);
        }
    }

    public void error(Throwable error) {
        try {
            synchronized (sendLock) {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(objectMapper.writeValueAsString(errorPayload(error))));
            }
        } catch (IOException ignored) {
            // 客户端已断开时无需二次处理。
        } finally {
            emitter.complete();
        }
    }

    static String eventName(AgentEvent event) {
        if (event.type() == com.pixflow.harness.loop.event.AgentEventType.ASSISTANT_MESSAGE_COMPLETED) {
            return "assistant_message_completed";
        }
        return event.type().name().toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> toPayload(AgentEvent event) {
        // 这里是 loop 内部事件到前端 SSE 契约的唯一投影点，避免把内部 envelope 泄漏给页面状态机。
        Map<String, Object> payload = basePayload(event);
        switch (event.type()) {
            case ASSISTANT_DELTA -> payload.put("text", event.text());
            case ASSISTANT_MESSAGE_COMPLETED -> {
                payload.put("finalText", event.text());
                payload.put("messageId", value(event.payload(), "messageId", metadata(event, "messageId", "")));
            }
            case TOOL_CALL_READY -> {
                payload.put("toolName", value(event.payload(), "toolName", value(event.payload(), "name", "")));
                payload.put("toolCallId", value(event.payload(), "toolCallId", value(event.payload(), "id", "")));
                payload.put("toolInput", toolInput(event.payload()));
            }
            case TOOL_STARTED -> {
                payload.put("toolCallId", value(event.payload(), "toolCallId", value(event.payload(), "id", "")));
                payload.put("toolName", value(event.payload(), "toolName", value(event.payload(), "name", "")));
            }
            case TOOL_RESULT -> {
                payload.put("toolCallId", value(event.payload(), "toolCallId", value(event.payload(), "id", "")));
                payload.put("toolName", value(event.payload(), "toolName", value(event.payload(), "name", "")));
                payload.put("content", value(event.payload(), "content", event.text()));
                payload.put("metadata", value(event.payload(), "metadata", Map.of()));
                payload.put("externalized", externalized(event.payload()));
                payload.put("error", toolError(event.payload()));
            }
            case TRANSITION -> {
                payload.put("reason", value(event.payload(), "reason", String.valueOf(event.payload())));
                putMetadataIfPresent(payload, event, "attempt");
                putMetadataIfPresent(payload, event, "retriesRemaining");
                putMetadataIfPresent(payload, event, "errorCode");
                putMetadataIfPresent(payload, event, "message");
                putMetadataIfPresent(payload, event, "retrying");
            }
            case COMPLETED -> payload.put("finalText", event.text());
        }
        return Map.copyOf(payload);
    }

    static Map<String, Object> errorPayload(Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (error instanceof PixFlowException pf) {
            payload.put("errorCode", pf.code().code());
            payload.put("message", safeMessage(pf.getMessage()));
            if (pf.traceId() != null && !pf.traceId().isBlank()) {
                payload.put("traceId", pf.traceId());
            }
            return Map.copyOf(payload);
        }
        payload.put("errorCode", CommonErrorCode.INTERNAL_ERROR.code());
        payload.put("message", safeMessage(error == null ? "unknown error" : error.getMessage()));
        return Map.copyOf(payload);
    }

    private static Map<String, Object> basePayload(AgentEvent event) {
        Map<String, Object> payload = new HashMap<>();
        putMetadataIfPresent(payload, event, "assistantCallId");
        putMetadataIfPresent(payload, event, "modelTurnIndex");
        putMetadataIfPresent(payload, event, "iteration");
        putMetadataIfPresent(payload, event, "traceId");
        putMetadataIfPresent(payload, event, "turnNo");
        return payload;
    }

    private static void putMetadataIfPresent(Map<String, Object> payload, AgentEvent event, String key) {
        if (event.metadata().containsKey(key)) {
            payload.put(key, event.metadata().get(key));
        }
    }

    private static Object metadata(AgentEvent event, String key, Object fallback) {
        return event.metadata().getOrDefault(key, fallback);
    }

    private static Object toolInput(Object payload) {
        Object direct = value(payload, "toolInput", null);
        if (direct != null) {
            return direct;
        }
        Object input = value(payload, "input", null);
        if (input != null) {
            return input;
        }
        Object arguments = value(payload, "arguments", null);
        if (arguments != null) {
            return arguments;
        }
        Object argumentsJson = value(payload, "argumentsJson", null);
        return argumentsJson == null ? Map.of() : argumentsJson;
    }

    private static Object externalized(Object payload) {
        Object direct = value(payload, "externalized", null);
        if (direct instanceof Boolean bool) {
            return bool;
        }
        Object metadata = value(payload, "metadata", Map.of());
        Object nested = value(metadata, "externalized", false);
        return nested instanceof Boolean bool ? bool : false;
    }

    private static boolean toolError(Object payload) {
        Object direct = value(payload, "error", null);
        return direct instanceof Boolean bool && bool;
    }

    private static Object value(Object payload, String key, Object fallback) {
        if (payload instanceof Map<?, ?> map && map.containsKey(key)) {
            Object value = map.get(key);
            return value == null ? fallback : value;
        }
        Method accessor = accessor(payload, key);
        if (accessor != null) {
            try {
                Object value = accessor.invoke(payload);
                return value == null ? fallback : value;
            } catch (ReflectiveOperationException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Method accessor(Object payload, String key) {
        if (payload == null || payload instanceof String) {
            return null;
        }
        Map<String, Method> accessors = ACCESSOR_CACHE.computeIfAbsent(payload.getClass(), SseAgentEventSink::scanAccessors);
        return accessors.get(key);
    }

    private static Map<String, Method> scanAccessors(Class<?> type) {
        Map<String, Method> result = new HashMap<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                result.put(component.getName(), accessor);
            }
            return Map.copyOf(result);
        }
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) {
                continue;
            }
            String property = propertyName(method.getName());
            if (property != null) {
                method.setAccessible(true);
                result.putIfAbsent(property, method);
            }
        }
        return Map.copyOf(result);
    }

    private static String propertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3 && !"getClass".equals(methodName)) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return decapitalize(methodName.substring(2));
        }
        return null;
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() > 1 && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String safeMessage(String raw) {
        String sanitized = Sanitizer.sanitizeMessage(raw);
        return sanitized == null || sanitized.isBlank() ? "unknown error" : sanitized;
    }
}
