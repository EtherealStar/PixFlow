package com.pixflow.module.conversation.proposal;

import com.pixflow.module.dag.propose.PendingPlan;
import java.time.Instant;

public record PendingProposal(
        String proposalId,
        String conversationId,
        PendingProposalType type,
        String payload,
        String packageId,
        String payloadHash,
        int expectedCount,
        PendingProposalStatus status,
        Instant createdAt) {

    public static PendingProposal from(PendingPlan plan) {
        PendingProposalType type = "IMAGEGEN".equalsIgnoreCase(plan.getType())
                ? PendingProposalType.IMAGEGEN
                : PendingProposalType.DAG;
        return new PendingProposal(
                String.valueOf(plan.getId()),
                plan.getConversationId(),
                type,
                plan.getDagJson(),
                parsePackageId(plan.getNote()),
                plan.getPayloadHash(),
                0,
                PendingProposalStatus.valueOf(plan.getStatus().name()),
                plan.getCreatedAt());
    }

    private static String parsePackageId(String note) {
        if (note == null || note.isBlank()) {
            return "0";
        }
        for (String part : note.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("packageId=")) {
                return trimmed.substring("packageId=".length());
            }
        }
        return "0";
    }
}
