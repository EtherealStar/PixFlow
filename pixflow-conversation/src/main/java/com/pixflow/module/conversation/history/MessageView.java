package com.pixflow.module.conversation.history;

import com.pixflow.harness.session.persistence.MessageReadView;
import java.time.Instant;

public record MessageView(
        String id,
        long seq,
        String role,
        String content,
        String toolCallId,
        String compactionMarker,
        String metadata,
        String attachedPackageId,
        String taskId,
        Instant createdAt,
        boolean compactionBoundary) {

    public static MessageView from(MessageReadView view) {
        return new MessageView(
                view.id(),
                view.seq() == null ? 0L : view.seq(),
                view.role(),
                view.content(),
                view.toolCallId(),
                view.compactionMarker(),
                view.metadata(),
                view.attachedPackageId(),
                view.taskId(),
                view.createdAt(),
                view.compactionMarker() != null && !view.compactionMarker().isBlank());
    }
}
