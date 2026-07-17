package com.pixflow.module.conversation.permission;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.proposal.ProposalService;

/** Conversation 属主对当前会话和 Proposal 事实的唯一解释器。 */
public final class ConversationPermissionProofs
        implements ConversationAuthorizationPort, ProposalAuthorizationPort {
    private final ConversationService conversationService;

    private final ProposalService proposalService;

    public ConversationPermissionProofs(
            ConversationService conversationService,
            ProposalService proposalService) {
        this.conversationService = conversationService;
        this.proposalService = proposalService;
    }

    @Override
    public ProofResult proveAccess(PermissionPrincipal principal, String conversationId) {
        try {
            conversationService.requireActive(Long.parseLong(principal.userId()), conversationId);
            return ProofResult.PROVED;
        } catch (PixFlowException | IllegalArgumentException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }

    @Override
    public ProofResult proveConfirmable(
            PermissionPrincipal principal,
            String conversationId,
            String proposalId,
            String payloadHash) {
        try {
            return proposalService.find(proposalId)
                    .filter(proposal -> conversationId.equals(proposal.conversationId()))
                    .filter(com.pixflow.module.conversation.proposal.ProposalSnapshot::confirmable)
                    .filter(proposal -> payloadHash.equals(proposal.payloadHash()))
                    .map(ignored -> ProofResult.PROVED)
                    .orElse(ProofResult.DENIED);
        } catch (PixFlowException | IllegalArgumentException denied) {
            return ProofResult.DENIED;
        } catch (RuntimeException unavailable) {
            return ProofResult.UNAVAILABLE;
        }
    }
}
