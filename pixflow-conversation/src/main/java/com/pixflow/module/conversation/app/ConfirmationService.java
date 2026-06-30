package com.pixflow.module.conversation.app;

import com.pixflow.common.error.BusinessException;
import com.pixflow.contracts.confirmation.ConfirmationAction;
import com.pixflow.contracts.confirmation.ConfirmationChallenge;
import com.pixflow.contracts.confirmation.ConfirmationChallengeStatus;
import com.pixflow.contracts.confirmation.ConfirmationLevel;
import com.pixflow.contracts.confirmation.ConfirmationToken;
import com.pixflow.contracts.confirmation.TokenClaims;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import com.pixflow.module.conversation.config.ConversationProperties;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.proposal.PendingProposal;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import com.pixflow.module.conversation.proposal.PendingProposalStatus;
import com.pixflow.module.conversation.proposal.PendingProposalType;
import com.pixflow.module.conversation.proposal.ProposalThreshold;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CreateTaskCommand;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.domain.model.TaskType;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConfirmationService {
    private final ConversationService conversationService;
    private final PendingProposalRepository proposalRepository;
    private final ProposalThreshold proposalThreshold;
    private final ConfirmationTokenService tokenService;
    private final TaskCommandService taskCommandService;
    private final ConversationProperties properties;
    private final Clock clock;
    private final Map<String, ConfirmationChallenge> challenges = new ConcurrentHashMap<>();

    public ConfirmationService(
            ConversationService conversationService,
            PendingProposalRepository proposalRepository,
            ProposalThreshold proposalThreshold,
            ConfirmationTokenService tokenService,
            TaskCommandService taskCommandService,
            ConversationProperties properties,
            Clock clock) {
        this.conversationService = conversationService;
        this.proposalRepository = proposalRepository;
        this.proposalThreshold = proposalThreshold;
        this.tokenService = tokenService;
        this.taskCommandService = taskCommandService;
        this.properties = properties;
        this.clock = clock;
    }

    public ConfirmationChallengeResponse challenge(String conversationId, String proposalId) {
        conversationService.requireActive(conversationId);
        PendingProposal proposal = requirePendingForConversation(conversationId, proposalId);
        if (!proposalThreshold.requiresChallenge(proposal)) {
            ConfirmationToken token = issueToken(proposal);
            return new ConfirmationChallengeResponse(false, null, token.tokenId());
        }
        Instant now = clock.instant();
        ConfirmationChallenge challenge = new ConfirmationChallenge(
                UUID.randomUUID().toString(),
                proposal.proposalId(),
                conversationId,
                "该提案将处理 " + proposal.expectedCount() + " 张图片，请输入“确认”继续。",
                ConfirmationChallengeStatus.PENDING,
                now,
                now.plus(properties.getConfirmation().getChallengeTtl()));
        // V1 用进程内状态保存 challenge；后续可替换为 permission/Redis 存储而不改变 controller 形态。
        challenges.put(challenge.challengeId(), challenge);
        return new ConfirmationChallengeResponse(true, challenge, null);
    }

    public ConfirmationSubmitResponse submit(
            String conversationId,
            String proposalId,
            ConfirmationSubmitRequest request) {
        conversationService.requireActive(conversationId);
        PendingProposal proposal = requirePendingForConversation(conversationId, proposalId);
        if (proposalThreshold.requiresChallenge(proposal)) {
            verifyChallenge(request);
        }
        issueToken(proposal);
        String taskId = createTaskIfPossible(proposal);
        proposalRepository.markConfirmed(proposal, taskId);
        return new ConfirmationSubmitResponse(proposal.proposalId(), taskId, "CONFIRMED");
    }

    private PendingProposal requirePendingForConversation(String conversationId, String proposalId) {
        PendingProposal proposal = proposalRepository.require(proposalId);
        if (!conversationId.equals(proposal.conversationId())) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_NOT_FOUND,
                    "proposal not found in conversation");
        }
        if (proposal.status() != PendingProposalStatus.PENDING) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_ALREADY_CONFIRMED,
                    "proposal is not pending");
        }
        return proposal;
    }

    private void verifyChallenge(ConfirmationSubmitRequest request) {
        if (request == null || request.challengeId() == null || request.challengeId().isBlank()) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_FAILED,
                    "challenge id is required");
        }
        ConfirmationChallenge challenge = challenges.get(request.challengeId());
        if (challenge == null || !challenge.expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_EXPIRED,
                    "challenge expired");
        }
        String answer = request.challengeAnswer() == null ? "" : request.challengeAnswer().trim();
        if (!properties.getConfirmation().getPermitLiteralAnswers().contains(answer)) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_FAILED,
                    "challenge answer rejected");
        }
        challenges.remove(request.challengeId());
    }

    private ConfirmationToken issueToken(PendingProposal proposal) {
        Instant now = clock.instant();
        return tokenService.issue(new TokenClaims(
                proposal.type() == PendingProposalType.IMAGEGEN
                        ? ConfirmationAction.IMAGEGEN
                        : ConfirmationAction.SUBMIT_DAG,
                proposal.conversationId(),
                proposal.packageId() == null || proposal.packageId().isBlank() ? "0" : proposal.packageId(),
                proposal.payloadHash(),
                proposal.expectedCount() > properties.getConfirmation().getBatchThreshold()
                        ? ConfirmationLevel.BULK
                        : ConfirmationLevel.NORMAL,
                proposal.expectedCount(),
                now,
                now.plus(properties.getConfirmation().getTokenTtl()),
                UUID.randomUUID().toString()));
    }

    private String createTaskIfPossible(PendingProposal proposal) {
        long packageId = parsePackageId(proposal.packageId());
        if (packageId <= 0L) {
            return null;
        }
        TaskType taskType = proposal.type() == PendingProposalType.IMAGEGEN ? TaskType.IMAGE_GEN : TaskType.IMAGE_PROCESS;
        TaskId taskId = taskCommandService.createAndEnqueue(new CreateTaskCommand(
                taskType,
                proposal.conversationId(),
                packageId,
                "proposal:" + proposal.proposalId(),
                proposal.payload(),
                0,
                proposal.expectedCount(),
                proposal.payloadHash()));
        return taskId.value();
    }

    private static long parsePackageId(String packageId) {
        try {
            return Long.parseLong(packageId);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
