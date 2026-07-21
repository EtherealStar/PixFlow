package com.pixflow.module.conversation.app;

import java.time.Instant;

public record ConversationView(
        String conversationId,
        String title,
        Instant createdAt,
        Instant updatedAt) {
}
