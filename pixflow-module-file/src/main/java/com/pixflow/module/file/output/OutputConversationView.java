package com.pixflow.module.file.output;

import java.time.Instant;

public record OutputConversationView(
        String conversationId,
        String title,
        long generatedImageCount,
        Instant latestGeneratedAt) {
}
