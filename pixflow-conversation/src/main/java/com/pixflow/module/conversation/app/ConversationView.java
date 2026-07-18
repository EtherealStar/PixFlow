package com.pixflow.module.conversation.app;

import com.pixflow.module.conversation.persistence.ConversationEntity;
import java.time.Instant;

public record ConversationView(
        String id,
        String title,
        boolean archived,
        Instant createdAt,
        Instant updatedAt) {

    public static ConversationView from(ConversationEntity entity) {
        return new ConversationView(
                entity.getId(),
                entity.getTitle(),
                Boolean.TRUE.equals(entity.getArchived()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
