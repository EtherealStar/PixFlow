package com.pixflow.app.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 已完成 owner 校验、可安全投影到当前 Agent SSE 的 Proposal 事件。 */
public record ProposalReadyEvent(
        String proposalId,
        String conversationId,
        String proposalType,
        String title,
        String summary,
        List<String> referenceSummaries,
        Instant createdAt) {
    public static final String METADATA_KEY = "pixflow.proposalReady";

    public ProposalReadyEvent {
        proposalId = Objects.requireNonNull(proposalId, "proposalId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        proposalType = Objects.requireNonNull(proposalType, "proposalType");
        title = Objects.requireNonNull(title, "title");
        summary = Objects.requireNonNull(summary, "summary");
        referenceSummaries = List.copyOf(referenceSummaries);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
