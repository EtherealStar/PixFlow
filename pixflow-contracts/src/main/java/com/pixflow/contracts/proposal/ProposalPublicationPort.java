package com.pixflow.contracts.proposal;

/** Conversation 拥有的临时 Proposal 发布入口。 */
public interface ProposalPublicationPort {
    String publish(ProposalDraft proposal);
}
