package com.pixflow.harness.loop;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.permission.PermissionPrincipal;
import java.util.List;
import java.util.Objects;

public record AgentTurnRequest(
        String conversationId,
        PermissionPrincipal principal,
        String prompt,
        List<Attachment> attachments,
        CancellationToken cancellation) {

    public AgentTurnRequest {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(principal, "principal");
        prompt = prompt == null ? "" : prompt;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
}
