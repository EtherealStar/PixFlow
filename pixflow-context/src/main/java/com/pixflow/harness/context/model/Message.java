package com.pixflow.harness.context.model;

import java.time.Instant;
import java.util.List;
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

    public static Message user(String content, List<MessageReference> references) {
        return new Message(
                null,
                MessageRole.USER,
                content,
                null,
                MessageMetadata.empty().withReferences(references),
                Instant.now());
    }

    public static Message userEvent(String content, MessageMetadata metadata) {
        return new Message(null, MessageRole.USER, content, null, metadata, Instant.now());
    }

    public static Message assistant(String content) {
        return new Message(null, MessageRole.ASSISTANT, content, null, MessageMetadata.empty(), Instant.now());
    }

    public static Message assistantToolCall(String content, java.util.List<AssistantToolCall> toolCalls) {
        return new Message(null, MessageRole.ASSISTANT, content, null,
                MessageMetadata.empty().with(MessageMetadata.ASSISTANT_TOOL_CALLS, toolCalls), Instant.now());
    }

    public static Message assistantToolCall(String content, String toolCallId) {
        return assistantToolCall(content, java.util.List.of(new AssistantToolCall(toolCallId, "unknown_tool", "{}")));
    }

    public static Message toolResult(String toolCallId, String content) {
        return new Message(null, MessageRole.TOOL_RESULT, content, toolCallId, MessageMetadata.empty(), Instant.now());
    }

    public Message withContent(String newContent) {
        return new Message(id, role, newContent, toolCallId, metadata, createdAt);
    }

    public Message withMetadata(MessageMetadata newMetadata) {
        return new Message(id, role, content, toolCallId, newMetadata, createdAt);
    }
}
