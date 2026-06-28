package com.pixflow.harness.context.store;

import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MessageStore {
    private final TranscriptPort transcriptPort;
    private final List<Message> messages = new ArrayList<>();
    private String conversationId;

    public MessageStore() {
        this(null);
    }

    public MessageStore(TranscriptPort transcriptPort) {
        this.transcriptPort = transcriptPort;
    }

    public void bindConversation(String conversationId) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
    }

    public Message appendUser(String content) {
        return appendOne(Message.user(content));
    }

    public Message appendAssistant(Message assistant) {
        if (assistant.role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("assistant message expected");
        }
        return appendOne(assistant);
    }

    public List<Message> appendToolResults(List<Message> results) {
        for (Message result : results) {
            if (result.role() != MessageRole.TOOL_RESULT) {
                throw new IllegalArgumentException("tool result message expected");
            }
        }
        return appendMany(results);
    }

    public List<Message> appendAttachments(List<Message> attachments) {
        for (Message attachment : attachments) {
            if (attachment.role() != MessageRole.ATTACHMENT) {
                throw new IllegalArgumentException("attachment message expected");
            }
        }
        return appendMany(attachments);
    }

    public List<Message> currentMessages() {
        return List.copyOf(messages);
    }

    public List<Message> seedMessages(List<Message> seed) {
        messages.clear();
        messages.addAll(seed == null ? List.of() : seed);
        return currentMessages();
    }

    public List<Message> replaceMessagesForCompaction(
            List<Message> replacement,
            CompactionTrigger trigger,
            Map<String, Object> metadata) {
        List<Message> safeReplacement = List.copyOf(replacement == null ? List.of() : replacement);
        List<Message> persisted = transcriptPort == null
                ? safeReplacement
                : transcriptPort.replaceForCompaction(requiredConversationId(), safeReplacement, trigger, metadata);
        messages.clear();
        messages.addAll(persisted);
        return currentMessages();
    }

    public void flush() {
        // 目前 append 已写穿透；保留显式 flush 入口给后续 session 实现对齐生命周期。
    }

    private Message appendOne(Message message) {
        return appendMany(List.of(message)).get(0);
    }

    private List<Message> appendMany(List<Message> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        List<Message> prepared = batch.stream()
                .map(message -> message.createdAt() == null
                        ? new Message(message.id(), message.role(), message.content(), message.toolCallId(), message.metadata(), Instant.now())
                        : message)
                .toList();
        List<Message> persisted = transcriptPort == null
                ? prepared
                : transcriptPort.append(requiredConversationId(), prepared);
        messages.addAll(persisted);
        return List.copyOf(persisted);
    }

    private String requiredConversationId() {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("conversationId is required when transcriptPort is configured");
        }
        return conversationId;
    }
}
