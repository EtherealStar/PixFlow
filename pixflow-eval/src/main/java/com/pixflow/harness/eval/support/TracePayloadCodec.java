package com.pixflow.harness.eval.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.eval.config.EvalProperties;
import com.pixflow.harness.eval.model.TraceExternalPayloadRef;
import com.pixflow.harness.eval.model.TurnTraceRecord;
import com.pixflow.harness.eval.recorder.TraceCommand;
import com.pixflow.harness.eval.store.AgentTraceEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TracePayloadCodec {
    private static final String EXTERNAL_MARKER = "__external";

    private final ObjectMapper objectMapper;

    private final EvalProperties properties;

    private final TraceExternalPayloadStorage externalStorage;

    public TracePayloadCodec(
            ObjectMapper objectMapper,
            EvalProperties properties,
            TraceExternalPayloadStorage externalStorage) {
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
        this.properties = properties;
        this.externalStorage = externalStorage;
    }

    public AgentTraceEntity encode(TraceCommand command) {
        Instant now = Instant.now();
        return new AgentTraceEntity(
                null,
                command.conversationId(),
                command.turnNo(),
                command.traceId(),
                properties.getSchemaVersion(),
                command.status(),
                command.runtimeScope(),
                encodeColumn(command.inputs()),
                encodeColumn(command.toolCalls()),
                encodeColumn(command.recalls()),
                encodeColumn(command.prunes()),
                encodeColumn(command.errors()),
                command.createdAt() == null ? now : command.createdAt(),
                command.updatedAt() == null ? now : command.updatedAt());
    }

    public RehydratedColumn decodeColumn(String json) {
        if (json == null || json.isBlank()) {
            return new RehydratedColumn("[]", false);
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            if (Boolean.TRUE.equals(map.get(EXTERNAL_MARKER))) {
                TraceExternalPayloadRef ref = new TraceExternalPayloadRef(
                        String.valueOf(map.get("key")),
                        asLong(map.get("size")),
                        stringValue(map.get("etag")),
                        stringValue(map.get("sha256")),
                        stringValue(map.get("preview")),
                        false);
                try {
                    return new RehydratedColumn(externalStorage.get(ref), false);
                } catch (RuntimeException ex) {
                    return new RehydratedColumn(ref.preview(), true);
                }
            }
        } catch (JsonProcessingException ignored) {
            return new RehydratedColumn(json, false);
        }
        return new RehydratedColumn(json, false);
    }

    public TurnTraceRecord toRecord(AgentTraceEntity entity) {
        return new TurnTraceRecord(
                entity.conversationId(),
                entity.turnNo(),
                entity.traceId(),
                entity.schemaVersion(),
                entity.turnStatus(),
                entity.runtimeScope(),
                entity.inputJson(),
                entity.toolCallsJson(),
                entity.recallJson(),
                entity.pruneLogJson(),
                entity.errorJson(),
                entity.createdAt(),
                entity.updatedAt());
    }

    private String encodeColumn(Object value) {
        Object tree = objectMapper.convertValue(value == null ? List.of() : value, Object.class);
        Object sanitized = Sanitizer.sanitizeTraceValue("trace", tree);
        String json = writeJson(sanitized);
        // 外置前已经完成统一脱敏，避免对象存储里留下未处理的敏感原文。
        if (json.length() <= properties.getColumnExternalizeThreshold()) {
            return json;
        }
        TraceExternalPayloadRef ref = externalStorage.put(json);
        return writeJson(Map.of(
                EXTERNAL_MARKER, true,
                "key", ref.key(),
                "size", ref.size(),
                "etag", ref.etag(),
                "sha256", ref.sha256(),
                "preview", ref.preview()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode()
                    .put("serializationError", Sanitizer.sanitizeTraceText(e.getMessage()))
                    .toString();
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record RehydratedColumn(String json, boolean missingExternal) {
    }
}
