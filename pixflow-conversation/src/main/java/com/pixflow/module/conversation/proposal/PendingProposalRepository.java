package com.pixflow.module.conversation.proposal;

import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.dag.propose.PendingPlan;
import com.pixflow.module.dag.propose.PendingPlanMapper;
import java.util.Optional;

public class PendingProposalRepository {
    private final PendingPlanMapper pendingPlanMapper;

    public PendingProposalRepository(PendingPlanMapper pendingPlanMapper) {
        this.pendingPlanMapper = pendingPlanMapper;
    }

    public Optional<PendingProposal> find(String proposalId) {
        Long id = parseId(proposalId);
        PendingPlan plan = pendingPlanMapper.findById(id);
        return plan == null ? Optional.empty() : Optional.of(PendingProposal.from(plan));
    }

    public PendingProposal require(String proposalId) {
        return find(proposalId)
                .orElseThrow(() -> new BusinessException(ConversationErrorCode.PROPOSAL_NOT_FOUND,
                        "proposal not found: " + proposalId));
    }

    public void markConfirmed(PendingProposal proposal, String taskId) {
        pendingPlanMapper.updateStatus(Long.parseLong(proposal.proposalId()), "CONFIRMED", java.time.Instant.now(), taskId);
    }

    private static Long parseId(String proposalId) {
        try {
            return Long.parseLong(proposalId);
        } catch (RuntimeException ex) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_NOT_FOUND,
                    "proposal not found: " + proposalId);
        }
    }
}
