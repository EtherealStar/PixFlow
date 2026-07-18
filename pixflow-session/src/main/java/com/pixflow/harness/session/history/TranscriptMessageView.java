package com.pixflow.harness.session.history;

import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.context.model.MessageRole;
import java.time.Instant;
import java.util.List;

/** 不暴露 raw metadata 或持久化实体的 typed transcript 视图。 */
public record TranscriptMessageView(
        String id,
        long seq,
        MessageRole role,
        String content,
        String toolCallId,
        List<MessageReference> references,
        String compactionMarker,
        String taskId,
        Instant createdAt) {

    public TranscriptMessageView {
        references = references == null ? List.of() : List.copyOf(references);
    }
}
