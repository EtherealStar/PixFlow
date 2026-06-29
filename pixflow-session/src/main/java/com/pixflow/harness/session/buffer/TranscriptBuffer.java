package com.pixflow.harness.session.buffer;

import com.pixflow.harness.context.model.Message;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TranscriptBuffer {
    private final Map<String, List<Message>> byConversation = new LinkedHashMap<>();
    private final int flushMaxMessages;
    private final long flushMaxBytes;

    public TranscriptBuffer(int flushMaxMessages, long flushMaxBytes) {
        this.flushMaxMessages = Math.max(1, flushMaxMessages);
        this.flushMaxBytes = Math.max(1, flushMaxBytes);
    }

    public boolean add(String conversationId, List<Message> messages) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        List<Message> pending = byConversation.computeIfAbsent(conversationId, ignored -> new ArrayList<>());
        pending.addAll(messages);
        return pending.size() >= flushMaxMessages || byteSize(pending) >= flushMaxBytes;
    }

    public List<Message> drain(String conversationId) {
        List<Message> pending = byConversation.remove(conversationId);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return List.copyOf(pending);
    }

    public Map<String, List<Message>> drainAll() {
        if (byConversation.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Message>> copy = new LinkedHashMap<>();
        byConversation.forEach((conversationId, messages) -> copy.put(conversationId, List.copyOf(messages)));
        byConversation.clear();
        return copy;
    }

    private static long byteSize(List<Message> messages) {
        long size = 0L;
        for (Message message : messages) {
            size += message.content().getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }
}
