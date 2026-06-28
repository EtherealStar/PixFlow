package com.pixflow.harness.context.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Message(
        String id,
        MessageRole role,
        String content,
        String toolCallId,
        MessageMetadata metadata,
        Instant createdAt) {

    public Message {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        role = Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        metadata = metadata == null ? MessageMetadata.empty() : metadata;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (role == MessageRole.TOOL_RESULT && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool result message must have toolCallId");
        }
    }

    public static Message user(String content) {
        return new Message(null, MessageRole.USER, content, null, MessageMetadata.empty(), Instant.now());
    }

    public static Message assistant(String content) {
        return new Message(null, MessageRole.ASSISTANT, content, null, MessageMetadata.empty(), Instant.now());
    }

    public static Message assistantToolCall(String content, String toolCallId) {
        return new Message(null, MessageRole.ASSISTANT, content, null,
                MessageMetadata.empty().with(MessageMetadata.TOOL_CALL_IDS, java.util.List.of(toolCallId)), Instant.now());
    }

    public static Message toolResult(String toolCallId, String content) {
        return new Message(null, MessageRole.TOOL_RESULT, content, toolCallId, MessageMetadata.empty(), Instant.now());
    }

    public static Message attachment(String content) {
        return new Message(null, MessageRole.ATTACHMENT, content, null, MessageMetadata.empty(), Instant.now());
    }

    public Message withContent(String newContent) {
        return new Message(id, role, newContent, toolCallId, metadata, createdAt);
    }

    public Message withMetadata(MessageMetadata newMetadata) {
        return new Message(id, role, content, toolCallId, newMetadata, createdAt);
    }
}
