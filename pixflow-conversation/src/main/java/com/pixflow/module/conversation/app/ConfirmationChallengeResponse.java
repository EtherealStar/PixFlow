package com.pixflow.module.conversation.app;

import com.pixflow.contracts.confirmation.ConfirmationChallenge;

public record ConfirmationChallengeResponse(
        boolean needChallenge,
        ConfirmationChallenge challenge,
        String token) {
}
