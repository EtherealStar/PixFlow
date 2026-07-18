package com.pixflow.module.conversation.history;

import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.session.history.TranscriptMessageView;
import java.time.Instant;
import java.util.List;

public record MessageView(
        String id,
        long seq,
        String role,
        String content,
        String toolCallId,
        List<MessageReference> references,
        String compactionMarker,
        String taskId,
        Instant createdAt,
        boolean compactionBoundary) {

    public MessageView {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public static MessageView from(TranscriptMessageView view) {
        return new MessageView(
                view.id(),
                view.seq(),
                view.role().name(),
                view.content(),
                view.toolCallId(),
                view.references(),
                view.compactionMarker(),
                view.taskId(),
                view.createdAt(),
                view.compactionMarker() != null && !view.compactionMarker().isBlank());
    }
}
