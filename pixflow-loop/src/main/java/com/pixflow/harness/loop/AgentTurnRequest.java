package com.pixflow.harness.loop;

import com.pixflow.common.concurrent.CancellationToken;
import java.util.List;
import java.util.Objects;

public record AgentTurnRequest(
        String conversationId,
        String prompt,
        List<Attachment> attachments,
        CancellationToken cancellation) {

    public AgentTurnRequest {
        Objects.requireNonNull(conversationId, "conversationId");
        prompt = prompt == null ? "" : prompt;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
}
