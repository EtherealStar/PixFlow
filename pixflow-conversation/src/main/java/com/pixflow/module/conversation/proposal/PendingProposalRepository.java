package com.pixflow.module.conversation.proposal;

import com.pixflow.common.error.BusinessException;
import com.pixflow.contracts.proposal.ProposalDraft;
import com.pixflow.contracts.proposal.ProposalPublicationPort;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Conversation 进程内 Proposal store；进程丢失即丢弃未确认 Proposal。 */
public final class PendingProposalRepository implements ProposalPublicationPort {
    private final ConcurrentMap<String, PendingProposal> proposals = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, String> proposalIdsByToolCall = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, CompletableFuture<String>> confirmationResults =
            new ConcurrentHashMap<>();

    @Override
    public String publish(ProposalDraft draft) {
        String id = proposalIdsByToolCall.computeIfAbsent(draft.toolCallId(), ignored -> {
            String proposalId = UUID.randomUUID().toString();
            proposals.put(proposalId, PendingProposal.pending(proposalId, draft));
            return proposalId;
        });
        return id;
    }

    public Optional<PendingProposal> find(String proposalId) {
        return Optional.ofNullable(proposals.get(proposalId));
    }

    public PendingProposal require(String proposalId) {
        return find(proposalId).orElseThrow(() -> new BusinessException(
                ConversationErrorCode.PROPOSAL_NOT_FOUND, "proposal not found"));
    }

    public ConfirmationClaim claimConfirmation(PendingProposal proposal) {
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<String> existing =
                confirmationResults.putIfAbsent(proposal.proposalId(), result);
        if (existing != null) {
            return new ConfirmationClaim(null, existing, false);
        }
        try {
            PendingProposal confirming = transition(
                    proposal.proposalId(), PendingProposalStatus.PENDING,
                    current -> copy(current, PendingProposalStatus.CONFIRMING, null));
            return new ConfirmationClaim(confirming, result, true);
        } catch (RuntimeException conflict) {
            confirmationResults.remove(proposal.proposalId(), result);
            throw conflict;
        }
    }

    public PendingProposal markConfirmedWithTask(PendingProposal proposal, String taskId) {
        PendingProposal confirmed = transition(proposal.proposalId(), PendingProposalStatus.CONFIRMING,
                current -> copy(current, PendingProposalStatus.CONFIRMED, taskId));
        CompletableFuture<String> result = confirmationResults.get(proposal.proposalId());
        if (result != null) {
            // 状态与 taskId 绑定完成后再唤醒竞争请求，确保它们观察到同一个幂等结果。
            result.complete(taskId);
        }
        return confirmed;
    }

    public void markConfirmationFailed(PendingProposal proposal) {
        proposals.computeIfPresent(proposal.proposalId(), (ignored, current) ->
                current.status() == PendingProposalStatus.CONFIRMING
                        ? copy(current, PendingProposalStatus.PENDING, null) : current);
        CompletableFuture<String> result = confirmationResults.remove(proposal.proposalId());
        if (result != null) {
            result.completeExceptionally(stateConflict());
        }
    }

    public void reject(PendingProposal proposal) {
        if (!proposals.remove(proposal.proposalId(), proposal)) {
            throw stateConflict();
        }
        proposalIdsByToolCall.values().removeIf(proposal.proposalId()::equals);
    }

    public void removeConfirmed(String proposalId) {
        proposals.remove(proposalId);
        proposalIdsByToolCall.values().removeIf(proposalId::equals);
        confirmationResults.remove(proposalId);
    }

    private PendingProposal transition(
            String proposalId,
            PendingProposalStatus expected,
            java.util.function.UnaryOperator<PendingProposal> update) {
        AtomicReference<PendingProposal> result = new AtomicReference<>();
        proposals.compute(proposalId, (ignored, current) -> {
            if (current == null || current.status() != expected) {
                throw stateConflict();
            }
            PendingProposal next = update.apply(current);
            result.set(next);
            return next;
        });
        return result.get();
    }

    private static PendingProposal copy(
            PendingProposal proposal, PendingProposalStatus status, String taskId) {
        return new PendingProposal(
                proposal.proposalId(), proposal.conversationId(), proposal.type(), proposal.payload(),
                proposal.packageId(), proposal.payloadHash(), proposal.expectedCount(),
                proposal.referenceKeys(), status, proposal.createdAt(), taskId);
    }

    private static BusinessException stateConflict() {
        return new BusinessException(
                ConversationErrorCode.PROPOSAL_ALREADY_CONFIRMED, "proposal is not pending");
    }

    /** CAS 认领结果：只有 owner 可以创建 Task，竞争者只等待同一完成信号。 */
    public record ConfirmationClaim(
            PendingProposal proposal, CompletableFuture<String> result, boolean owner) {
    }
}
