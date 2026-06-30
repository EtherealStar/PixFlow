package com.pixflow.contracts.confirmation;

import java.time.Instant;
import java.util.Objects;

public record ConfirmationChallenge(
        String challengeId,
        String proposalId,
        String conversationId,
        String prompt,
        ConfirmationChallengeStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public ConfirmationChallenge {
        requireText(challengeId, "challengeId");
        requireText(proposalId, "proposalId");
        requireText(conversationId, "conversationId");
        requireText(prompt, "prompt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
