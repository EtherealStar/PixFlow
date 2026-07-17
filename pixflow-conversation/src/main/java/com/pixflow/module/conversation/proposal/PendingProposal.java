package com.pixflow.module.conversation.proposal;

import java.time.Instant;
import java.util.List;

record PendingProposal(
        String proposalId,
        String conversationId,
        PendingProposalType type,
        String payload,
        long packageId,
        String payloadHash,
        int expectedCount,
        List<String> referenceKeys,
        PendingProposalStatus status,
        Instant createdAt,
        String taskId) {

    public PendingProposal {
        referenceKeys = referenceKeys == null ? List.of() : List.copyOf(referenceKeys);
    }

    public static PendingProposal pending(String proposalId, PublishProposalCommand command) {
        return new PendingProposal(
                proposalId,
                command.conversationId(),
                command.type(),
                command.canonicalPayload(),
                command.packageId(),
                command.payloadHash(),
                command.expectedCount(),
                command.referenceKeys(),
                PendingProposalStatus.PENDING,
                command.createdAt(),
                null);
    }
}
