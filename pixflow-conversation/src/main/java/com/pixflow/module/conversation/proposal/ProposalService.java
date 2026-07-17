package com.pixflow.module.conversation.proposal;

import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Conversation 对临时 Proposal 发布、幂等与状态转换的唯一 owner service。 */
public final class ProposalService {
    private final ConcurrentMap<String, PendingProposal> proposals = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, String> proposalIdsByToolCall = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, CompletableFuture<String>> confirmationResults =
            new ConcurrentHashMap<>();

    public ProposalView publish(PublishProposalCommand command) {
        String id = proposalIdsByToolCall.computeIfAbsent(command.toolCallId(), ignored -> {
            String proposalId = UUID.randomUUID().toString();
            proposals.put(proposalId, PendingProposal.pending(proposalId, command));
            return proposalId;
        });
        PendingProposal proposal = requireEntity(id);
        return new ProposalView(id, proposal.payloadHash());
    }

    public Optional<ProposalSnapshot> find(String proposalId) {
        return Optional.ofNullable(proposals.get(proposalId)).map(ProposalService::snapshot);
    }

    public ProposalSnapshot require(String proposalId) {
        return snapshot(requireEntity(proposalId));
    }

    private PendingProposal requireEntity(String proposalId) {
        return Optional.ofNullable(proposals.get(proposalId)).orElseThrow(() -> new BusinessException(
                ConversationErrorCode.PROPOSAL_NOT_FOUND, "proposal not found"));
    }

    /** 在 owner service 内完成认领、等待、回滚与唤醒，不向调用方泄漏 CAS 接缝。 */
    public String confirm(
            String proposalId,
            Function<ProposalSnapshot, String> taskCreator) {
        PendingProposal proposal = requireEntity(proposalId);
        ConfirmationClaim claim = claimConfirmation(proposal);
        if (!claim.owner()) {
            return claim.result().join();
        }
        PendingProposal confirming = claim.proposal();
        try {
            String taskId = taskCreator.apply(snapshot(confirming));
            markConfirmedWithTask(confirming, taskId);
            removeConfirmed(confirming.proposalId());
            return taskId;
        } catch (RuntimeException failure) {
            markConfirmationFailed(confirming);
            throw failure;
        }
    }

    private ConfirmationClaim claimConfirmation(PendingProposal proposal) {
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

    private PendingProposal markConfirmedWithTask(PendingProposal proposal, String taskId) {
        PendingProposal confirmed = transition(proposal.proposalId(), PendingProposalStatus.CONFIRMING,
                current -> copy(current, PendingProposalStatus.CONFIRMED, taskId));
        CompletableFuture<String> result = confirmationResults.get(proposal.proposalId());
        if (result != null) {
            // 状态和 taskId 绑定完成后再唤醒竞争请求，保证它们观察同一幂等结果。
            result.complete(taskId);
        }
        return confirmed;
    }

    private void markConfirmationFailed(PendingProposal proposal) {
        proposals.computeIfPresent(proposal.proposalId(), (ignored, current) ->
                current.status() == PendingProposalStatus.CONFIRMING
                        ? copy(current, PendingProposalStatus.PENDING, null) : current);
        CompletableFuture<String> result = confirmationResults.remove(proposal.proposalId());
        if (result != null) {
            result.completeExceptionally(stateConflict());
        }
    }

    public void reject(String proposalId) {
        PendingProposal proposal = requireEntity(proposalId);
        if (proposal.status() != PendingProposalStatus.PENDING) {
            throw stateConflict();
        }
        if (!proposals.remove(proposal.proposalId(), proposal)) {
            throw stateConflict();
        }
        proposalIdsByToolCall.values().removeIf(proposal.proposalId()::equals);
    }

    private void removeConfirmed(String proposalId) {
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

    private static ProposalSnapshot snapshot(PendingProposal proposal) {
        PendingProposalStatus status = proposal.status();
        return new ProposalSnapshot(
                proposal.proposalId(), proposal.conversationId(), proposal.type(), proposal.payload(),
                proposal.packageId(), proposal.payloadHash(), proposal.expectedCount(),
                proposal.referenceKeys(),
                status == PendingProposalStatus.PENDING
                        || status == PendingProposalStatus.CONFIRMING,
                status == PendingProposalStatus.PENDING,
                status == PendingProposalStatus.CONFIRMED,
                proposal.taskId());
    }

    private static BusinessException stateConflict() {
        return new BusinessException(
                ConversationErrorCode.PROPOSAL_ALREADY_CONFIRMED, "proposal is not pending");
    }

    private record ConfirmationClaim(
            PendingProposal proposal, CompletableFuture<String> result, boolean owner) {
    }
}
