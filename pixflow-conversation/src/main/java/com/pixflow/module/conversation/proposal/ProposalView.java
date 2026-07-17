package com.pixflow.module.conversation.proposal;

/** 发布后返回给 App 编排层的最小视图。 */
public record ProposalView(String proposalId, String payloadHash) {
}
