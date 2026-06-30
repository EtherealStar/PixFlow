package com.pixflow.module.conversation.proposal;

import com.pixflow.module.conversation.config.ConversationProperties;

public class ProposalThreshold {
    private final ConversationProperties properties;

    public ProposalThreshold(ConversationProperties properties) {
        this.properties = properties;
    }

    public boolean requiresChallenge(PendingProposal proposal) {
        return proposal.expectedCount() > properties.getConfirmation().getBatchThreshold();
    }
}
