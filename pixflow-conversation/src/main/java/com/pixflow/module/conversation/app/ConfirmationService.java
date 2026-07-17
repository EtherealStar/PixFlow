package com.pixflow.module.conversation.app;

import com.pixflow.common.error.BusinessException;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.permission.PermissionAction;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionPlanMode;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.PermissionRuntimeScope;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.proposal.PendingProposal;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import com.pixflow.module.conversation.proposal.PendingProposalStatus;
import com.pixflow.module.conversation.proposal.PendingProposalType;
import com.pixflow.module.conversation.proposal.ProposalPayloadVerifier;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CreateTaskCommand;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.domain.model.TaskType;
import java.util.Map;

/** Proposal 的单次直接确认边界。 */
public final class ConfirmationService {
    private final ConversationService conversationService;

    private final PendingProposalRepository proposalRepository;

    private final PermissionPolicy permissionPolicy;

    private final TaskCommandService taskCommandService;

    private final ProposalPayloadVerifier payloadVerifier;

    public ConfirmationService(
            ConversationService conversationService,
            PendingProposalRepository proposalRepository,
            PermissionPolicy permissionPolicy,
            TaskCommandService taskCommandService,
            ProposalPayloadVerifier payloadVerifier) {
        this.conversationService = conversationService;
        this.proposalRepository = proposalRepository;
        this.permissionPolicy = permissionPolicy;
        this.taskCommandService = taskCommandService;
        this.payloadVerifier = payloadVerifier;
    }

    public ConfirmationSubmitResponse confirm(
            AuthPrincipal authenticated,
            String conversationId,
            String proposalId) {
        conversationService.requireActive(authenticated.userId(), conversationId);
        String idempotencyKey = idempotencyKey(proposalId);
        var existingTask = taskCommandService.findByIdempotencyKey(idempotencyKey);
        if (existingTask.isPresent()) {
            authorizeTaskReplay(
                    permissionPrincipal(authenticated), conversationId,
                    existingTask.orElseThrow().value(), proposalId);
            return new ConfirmationSubmitResponse(
                    proposalId, existingTask.orElseThrow().value(), "CONFIRMED");
        }
        PendingProposal proposal = requireForConfirmation(conversationId, proposalId);
        PermissionPrincipal principal = permissionPrincipal(authenticated);

        if (proposal.status() == PendingProposalStatus.CONFIRMED) {
            authorizeConversationOnly(principal, conversationId, proposalId);
            return confirmedResponse(proposal);
        }

        authorizeProposal(principal, proposal);
        PendingProposalRepository.ConfirmationClaim claim =
                proposalRepository.claimConfirmation(proposal);
        if (!claim.owner()) {
            String taskId = claim.result().join();
            return new ConfirmationSubmitResponse(proposalId, taskId, "CONFIRMED");
        }
        PendingProposal confirming = claim.proposal();

        try {
            String taskId = createTask(confirming);
            PendingProposal confirmed = proposalRepository.markConfirmedWithTask(confirming, taskId);
            ConfirmationSubmitResponse response = confirmedResponse(confirmed);
            // Task 已持久化并以 proposalId 幂等后，临时 Proposal 不再保留。
            proposalRepository.removeConfirmed(proposalId);
            return response;
        } catch (RuntimeException failure) {
            proposalRepository.markConfirmationFailed(confirming);
            throw failure;
        }
    }

    public void reject(AuthPrincipal authenticated, String conversationId, String proposalId) {
        conversationService.requireActive(authenticated.userId(), conversationId);
        PendingProposal proposal = proposalRepository.find(proposalId).orElse(null);
        if (proposal == null) {
            authorizeConversationOnly(permissionPrincipal(authenticated), conversationId, proposalId);
            return;
        }
        proposal = requirePending(conversationId, proposalId);
        authorizeProposal(permissionPrincipal(authenticated), proposal);
        proposalRepository.reject(proposal);
    }

    private PendingProposal requireForConfirmation(String conversationId, String proposalId) {
        PendingProposal proposal = proposalRepository.require(proposalId);
        if (!conversationId.equals(proposal.conversationId())) {
            throw proposalNotFound();
        }
        if (proposal.status() != PendingProposalStatus.PENDING
                && proposal.status() != PendingProposalStatus.CONFIRMING
                && proposal.status() != PendingProposalStatus.CONFIRMED) {
            throw new BusinessException(
                    ConversationErrorCode.PROPOSAL_ALREADY_CONFIRMED,
                    "proposal is not pending");
        }
        return proposal;
    }

    private PendingProposal requirePending(String conversationId, String proposalId) {
        PendingProposal proposal = proposalRepository.require(proposalId);
        if (!conversationId.equals(proposal.conversationId())) {
            throw proposalNotFound();
        }
        if (proposal.status() != PendingProposalStatus.PENDING) {
            throw new BusinessException(
                    ConversationErrorCode.PROPOSAL_ALREADY_CONFIRMED,
                    "proposal is not pending");
        }
        return proposal;
    }

    private void authorizeProposal(PermissionPrincipal principal, PendingProposal proposal) {
        if (!payloadVerifier.matches(proposal)) {
            throw new BusinessException(
                    ConversationErrorCode.PROPOSAL_PAYLOAD_MISMATCH,
                    "proposal payload hash mismatch");
        }
        PermissionDecision decision = permissionPolicy.evaluate(
                context(principal, proposal.conversationId(), "confirm:" + proposal.proposalId()),
                new PermissionSubject.ProposalConfirmation(
                        proposal.proposalId(), proposal.referenceKeys(), proposal.payloadHash()));
        requireAllowed(decision);
    }

    private void authorizeConversationOnly(
            PermissionPrincipal principal, String conversationId, String proposalId) {
        PermissionDecision decision = permissionPolicy.evaluate(
                context(principal, conversationId, "replay:" + proposalId),
                new PermissionSubject.ToolInvocation(
                        "confirm_proposal_replay", false, Map.of()));
        requireAllowed(decision);
    }

    private void authorizeTaskReplay(
            PermissionPrincipal principal,
            String conversationId,
            String taskId,
            String proposalId) {
        PermissionDecision decision = permissionPolicy.evaluate(
                context(principal, conversationId, "replay:" + proposalId),
                new PermissionSubject.TaskCommand(
                        taskId, com.pixflow.harness.permission.TaskCommandType.CONFIRM_REPLAY));
        requireAllowed(decision);
    }

    private static PermissionContext context(
            PermissionPrincipal principal, String conversationId, String callId) {
        return new PermissionContext(
                principal,
                PermissionRuntimeScope.MAIN,
                PermissionPlanMode.OFF,
                conversationId,
                callId);
    }

    private static void requireAllowed(PermissionDecision decision) {
        if (decision.action() == PermissionAction.DENY) {
            throw new PixFlowException(decision.errorCode(), decision.reason());
        }
    }

    private String createTask(PendingProposal proposal) {
        long packageId = parsePackageId(proposal.packageId());
        if (packageId <= 0L) {
            throw new BusinessException(
                    ConversationErrorCode.PACKAGE_REFERENCE_INVALID,
                    "proposal package id is invalid");
        }
        TaskType type = proposal.type() == PendingProposalType.IMAGEGEN
                ? TaskType.IMAGE_GEN
                : TaskType.IMAGE_PROCESS;
        TaskId taskId = taskCommandService.createAndEnqueue(new CreateTaskCommand(
                type,
                proposal.conversationId(),
                packageId,
                idempotencyKey(proposal.proposalId()),
                proposal.payload(),
                0,
                proposal.expectedCount(),
                proposal.payloadHash()));
        return taskId.value();
    }

    private static PermissionPrincipal permissionPrincipal(AuthPrincipal principal) {
        return new PermissionPrincipal(Long.toString(principal.userId()), principal.username());
    }

    private static ConfirmationSubmitResponse confirmedResponse(PendingProposal proposal) {
        return new ConfirmationSubmitResponse(proposal.proposalId(), proposal.taskId(), "CONFIRMED");
    }

    private static BusinessException proposalNotFound() {
        return new BusinessException(
                ConversationErrorCode.PROPOSAL_NOT_FOUND,
                "proposal not found in conversation");
    }

    private static long parsePackageId(String packageId) {
        try {
            return Long.parseLong(packageId);
        } catch (RuntimeException invalid) {
            return 0L;
        }
    }

    private static String idempotencyKey(String proposalId) {
        return "proposal:" + proposalId;
    }
}
