package com.pixflow.module.conversation.proposal;

import com.pixflow.contracts.proposal.ProposalDraft;
import java.time.Instant;
import java.util.List;

public record PendingProposal(
        String proposalId,
        String conversationId,
        PendingProposalType type,
        String payload,
        String packageId,
        String payloadHash,
        int expectedCount,
        List<String> referenceKeys,
        PendingProposalStatus status,
        Instant createdAt,
        String taskId) {

    public PendingProposal {
        referenceKeys = referenceKeys == null ? List.of() : List.copyOf(referenceKeys);
    }

    public static PendingProposal pending(String proposalId, ProposalDraft draft) {
        PendingProposalType type;
        try {
            type = PendingProposalType.valueOf(draft.proposalType().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException unsupportedType) {
            // 未知 Proposal 类型不能降级成 DAG，否则会跨越错误的任务授权边界。
            throw new IllegalArgumentException("unsupported proposal type", unsupportedType);
        }
        return new PendingProposal(
                proposalId,
                draft.conversationId(),
                type,
                draft.payloadJson(),
                draft.packageId(),
                draft.payloadHash(),
                draft.expectedCount(),
                draft.referenceKeys(),
                PendingProposalStatus.PENDING,
                draft.createdAt(),
                null);
    }
}
