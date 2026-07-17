package com.pixflow.contracts.proposal;

import java.time.Instant;
import java.util.List;

/** 已由属主模块完整校验、等待 Conversation 发布的不可变 Proposal。 */
public record ProposalDraft(
        String proposalType,
        String payloadJson,
        String conversationId,
        String packageId,
        String toolCallId,
        String payloadHash,
        int expectedCount,
        List<String> referenceKeys,
        Instant createdAt) {
    public ProposalDraft {
        proposalType = requireText(proposalType, "proposalType");
        payloadJson = requireText(payloadJson, "payloadJson");
        conversationId = requireText(conversationId, "conversationId");
        packageId = packageId == null ? "" : packageId.trim();
        toolCallId = requireText(toolCallId, "toolCallId");
        payloadHash = requireText(payloadHash, "payloadHash");
        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount must not be negative");
        }
        referenceKeys = referenceKeys == null ? List.of() : List.copyOf(referenceKeys);
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
