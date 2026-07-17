package com.pixflow.module.conversation.proposal;

import java.util.List;

/** Conversation 对确认/权限协作者公开的只读 Proposal 事实，不暴露内部 CAS 状态。 */
public record ProposalSnapshot(
        String proposalId,
        String conversationId,
        PendingProposalType type,
        String payload,
        long packageId,
        String payloadHash,
        int expectedCount,
        List<String> referenceKeys,
        boolean confirmable,
        boolean rejectable,
        boolean confirmed,
        String taskId) {

    public ProposalSnapshot {
        referenceKeys = List.copyOf(referenceKeys);
    }
}
