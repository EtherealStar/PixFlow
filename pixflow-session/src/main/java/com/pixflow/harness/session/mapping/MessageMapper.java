package com.pixflow.harness.session.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.session.persistence.MessageEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MessageMapper {
    public static final String MARKER_BOUNDARY = "BOUNDARY";

    public static final String MARKER_SUMMARY = "SUMMARY";

    public static final String SEQ = "seq";

    public static final String ATTACHED_PACKAGE_ID = "attachedPackageId";

    public static final String TASK_ID = "taskId";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public MessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public MessageEntity toEntity(String conversationId, Message message, long seq) {
        MessageEntity entity = new MessageEntity();
        entity.setId(message.id());
        entity.setConversationId(conversationId);
        entity.setSeq(seq);
        entity.setRole(message.role().name());
        entity.setContent(message.content());
        entity.setToolCallId(message.toolCallId());
        entity.setCompactionMarker(marker(message.metadata()));
        entity.setMetadata(toJson(withSeq(message.metadata(), seq)));
        entity.setAttachedPackageId(stringValue(message.metadata(), ATTACHED_PACKAGE_ID));
        entity.setTaskId(stringValue(message.metadata(), TASK_ID));
        entity.setCreatedAt(message.createdAt() == null ? Instant.now() : message.createdAt());
        return entity;
    }

    public Message toMessage(MessageEntity entity) {
        MessageMetadata metadata = fromJson(entity.getMetadata()).with(SEQ, entity.getSeq());
        return new Message(
                entity.getId(),
                MessageRole.valueOf(entity.getRole()),
                entity.getContent(),
                entity.getToolCallId(),
                metadata,
                entity.getCreatedAt());
    }

    public String toJson(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize metadata", ex);
        }
    }

    public MessageMetadata fromJson(String json) {
        if (json == null || json.isBlank()) {
            return MessageMetadata.empty();
        }
        try {
            return new MessageMetadata(objectMapper.readValue(json, MAP_TYPE));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to deserialize metadata", ex);
        }
    }

    private Map<String, Object> withSeq(MessageMetadata metadata, long seq) {
        Map<String, Object> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata.values());
        values.put(SEQ, seq);
        return values;
    }

    private static String marker(MessageMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        if (metadata.flag(MessageMetadata.COMPACT_BOUNDARY)) {
            return MARKER_BOUNDARY;
        }
        if (metadata.flag(MessageMetadata.COMPACT_SUMMARY)) {
            return MARKER_SUMMARY;
        }
        return null;
    }

    private static String stringValue(MessageMetadata metadata, String key) {
        Object value = metadata == null ? null : metadata.values().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
