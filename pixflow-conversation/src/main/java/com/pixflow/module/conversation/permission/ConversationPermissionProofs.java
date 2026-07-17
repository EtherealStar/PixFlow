package com.pixflow.module.conversation.permission;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import com.pixflow.module.conversation.proposal.PendingProposalStatus;

/** Conversation 属主对当前会话和 Proposal 事实的唯一解释器。 */
public final class ConversationPermissionProofs
        implements ConversationAuthorizationPort, ProposalAuthorizationPort {
    private final ConversationService conversationService;

    private final PendingProposalRepository proposalRepository;

    public ConversationPermissionProofs(
            ConversationService conversationService,
            PendingProposalRepository proposalRepository) {
        this.conversationService = conversationService;
        this.proposalRepository = proposalRepository;
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
            return proposalRepository.find(proposalId)
                    .filter(proposal -> conversationId.equals(proposal.conversationId()))
                    .filter(proposal -> proposal.status() == PendingProposalStatus.PENDING
                            || proposal.status() == PendingProposalStatus.CONFIRMING)
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
