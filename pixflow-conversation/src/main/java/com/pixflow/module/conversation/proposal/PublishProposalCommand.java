package com.pixflow.module.conversation.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 发布一个已由 producer 深校验的临时 Proposal。 */
public record PublishProposalCommand(
        PendingProposalType type,
        String conversationId,
        long packageId,
        String toolCallId,
        String canonicalPayload,
        String payloadHash,
        int expectedCount,
        List<String> referenceKeys,
        Instant createdAt) {

    public PublishProposalCommand {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        requireText(conversationId, "conversationId");
        if (packageId <= 0) {
            throw new IllegalArgumentException("packageId must be positive");
        }
        requireText(toolCallId, "toolCallId");
        requireText(canonicalPayload, "canonicalPayload");
        requireText(payloadHash, "payloadHash");
        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount must not be negative");
        }
        referenceKeys = List.copyOf(Objects.requireNonNull(referenceKeys, "referenceKeys"));
        if (referenceKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("referenceKeys must contain canonical keys");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
