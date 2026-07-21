package com.pixflow.module.conversation.history;

import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.session.history.TranscriptMessageView;
import java.time.Instant;
import java.util.List;

public record MessageView(
        String messageId,
        long seq,
        String role,
        String content,
        List<MessageReference> references,
        Instant createdAt) {

    public MessageView {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public static MessageView from(TranscriptMessageView view) {
        return new MessageView(
                view.id(),
                view.seq(),
                view.role().name(),
                view.content(),
                view.references(),
                view.createdAt());
    }
}
