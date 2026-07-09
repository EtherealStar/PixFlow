package com.pixflow.module.conversation.app;

import com.pixflow.common.error.BusinessException;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.confirmation.ConfirmationAction;
import com.pixflow.contracts.confirmation.ConfirmationChallenge;
import com.pixflow.contracts.confirmation.ConfirmationChallengeStatus;
import com.pixflow.contracts.confirmation.ConfirmationLevel;
import com.pixflow.contracts.confirmation.ConfirmationToken;
import com.pixflow.contracts.confirmation.TokenClaims;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.store.CacheStore;
import com.pixflow.module.conversation.config.ConversationProperties;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.lock.ConversationLock;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Confirmation 二阶段编排。
 *
 * <p>状态布局:
 * <ul>
 *   <li>{@code challenge → token}:高阈值时写入 Redis,key = {@code confirm:challenge:{challengeId}},
 *       TTL = {@code pixflow.conversation.confirmation.challenge-ttl}。</li>
 *   <li>{@code proposal 状态}:见 {@link PendingProposalRepository},由 dag 模块的
 *       {@code PendingPlanService.confirm(...)} 提供事务 + CAS 保护。</li>
 * </ul>
 *
 * <p>锁约束:本服务整体处于"提案确认阶段",与 agent 回合的 {@link ConversationLock}
 * 互不耦合——确认阶段不需要串行回合,但 {@link #submit} 内部需要把 proposal 行锁 / CAS,
 * 避免并发 confirm 重复创建任务(见 {@link com.pixflow.module.dag.propose.PendingPlanService#confirm})。
 */
public class ConfirmationService {
    private static final String CHALLENGE_KEY_NAMESPACE = "conversation.confirm.challenge";

    private final ConversationService conversationService;
    private final PendingProposalRepository proposalRepository;
    private final ProposalThreshold proposalThreshold;
    private final ConfirmationTokenService tokenService;
    private final TaskCommandService taskCommandService;
    private final ConversationProperties properties;
    private final CacheStore cacheStore;
    private final Clock clock;

    public ConfirmationService(
            ConversationService conversationService,
            PendingProposalRepository proposalRepository,
            ProposalThreshold proposalThreshold,
            ConfirmationTokenService tokenService,
            TaskCommandService taskCommandService,
            ConversationProperties properties,
            CacheStore cacheStore,
            Clock clock) {
        this.conversationService = conversationService;
        this.proposalRepository = proposalRepository;
        this.proposalThreshold = proposalThreshold;
        this.tokenService = tokenService;
        this.taskCommandService = taskCommandService;
        this.properties = properties;
        this.cacheStore = cacheStore;
        this.clock = clock;
    }

    /**
     * 阶段一:发起确认。
     *
     * <p>低阈值(无需 challenge):仅返回 {@code token},由前端拿到后直接调用
     * {@link #submit};后端 {@code submit} 不读 challenge 表,只对该 proposal 行做 CAS。
     * 高阈值(需 challenge):创建 challenge 写入 Redis,返回 {@code challenge} 让前端
     * 回填 challengeId + answer。
     */
    public ConfirmationChallengeResponse challenge(long ownerUserId, String conversationId, String proposalId) {
        conversationService.requireActive(ownerUserId, conversationId);
        PendingProposal proposal = requirePendingForConversation(conversationId, proposalId);
        if (!proposalThreshold.requiresChallenge(proposal)) {
            // 低阈值:不留 challenge,也不签 token。前端拿到 needChallenge=false 后
            // 直接调 submit,由 submit 内部统一签发 + verifyAndConsume。
            return new ConfirmationChallengeResponse(false, null, null);
        }
        Instant now = clock.instant();
        Duration ttl = properties.getConfirmation().getChallengeTtl();
        ConfirmationChallenge challenge = new ConfirmationChallenge(
                UUID.randomUUID().toString(),
                proposal.proposalId(),
                conversationId,
                "该提案将处理 " + proposal.expectedCount() + " 张图片，请输入“确认”继续。",
                ConfirmationChallengeStatus.PENDING,
                now,
                now.plus(ttl));
        CacheKey key = challengeKey(challenge.challengeId());
        cacheStore.put(key, challenge, ttl);
        return new ConfirmationChallengeResponse(true, challenge, null);
    }

    /**
     * 阶段二:提交确认。
     *
     * <p>低阈值路径:客户端传 {@code challengeId = null / blank + 不带 challengeAnswer},
     * 直接走 token 签发 + verifyAndConsume。
     * 高阈值路径:客户端必须回传 {@code challengeId + challengeAnswer},从 Redis 取出
     * challenge 比对答案。
     */
    public ConfirmationSubmitResponse submit(
            long ownerUserId,
            String conversationId,
            String proposalId,
            ConfirmationSubmitRequest request) {
        conversationService.requireActive(ownerUserId, conversationId);
        PendingProposal proposal = requireForSubmit(conversationId, proposalId);
        if (proposal.status() == PendingProposalStatus.CONFIRMED) {
            // 幂等:已被并发 confirm,直接回既有 taskId,不做任何副作用。
            return new ConfirmationSubmitResponse(proposal.proposalId(), proposal.taskId(), "CONFIRMED");
        }
        boolean highThreshold = proposalThreshold.requiresChallenge(proposal);
        if (highThreshold) {
            verifyChallenge(conversationId, proposalId, request);
        }
        ConfirmationToken token = issueToken(proposal);
        verifyAndConsume(token, proposal);
        PendingProposal confirming;
        try {
            confirming = proposalRepository.startConfirmation(proposal);
        } catch (PixFlowException ex) {
            // CAS 竞争:另一个请求已经先一步把它 mark 掉了。重读最新 status 给幂等响应。
            String ec = ex.code().code();
            if (!"DAG_PLAN_ALREADY_CONFIRMED".equals(ec)) {
                throw ex;
            }
            PendingProposal latest = proposalRepository.require(proposalId);
            if (latest.status() == PendingProposalStatus.CONFIRMED && latest.taskId() != null) {
                return confirmedResponse(latest);
            }
            throw ex;
        }
        String taskId;
        try {
            taskId = createTaskIfPossible(confirming);
            PendingProposal confirmed = proposalRepository.markConfirmedWithTask(confirming, taskId);
            return new ConfirmationSubmitResponse(confirmed.proposalId(), confirmed.taskId(), "CONFIRMED");
        } catch (RuntimeException ex) {
            proposalRepository.markConfirmationFailed(confirming);
            throw ex;
        }
    }

    private PendingProposal requireForSubmit(String conversationId, String proposalId) {
        PendingProposal proposal = proposalRepository.require(proposalId);
        if (!conversationId.equals(proposal.conversationId())) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_NOT_FOUND,
                    "proposal not found in conversation");
        }
        if (proposal.status() == PendingProposalStatus.CONFIRMED) {
            return proposal;
        }
        if (proposal.status() != PendingProposalStatus.PENDING) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_ALREADY_CONFIRMED,
                    "proposal is not pending");
        }
        return proposal;
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

    private void verifyChallenge(String conversationId, String proposalId, ConfirmationSubmitRequest request) {
        if (request == null || request.challengeId() == null || request.challengeId().isBlank()) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_FAILED,
                    "challenge id is required");
        }
        CacheKey key = challengeKey(request.challengeId());
        Optional<ConfirmationChallenge> stored = cacheStore.consume(key, ConfirmationChallenge.class);
        if (stored.isEmpty() || !stored.get().expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_EXPIRED,
                    "challenge expired");
        }
        ConfirmationChallenge challenge = stored.get();
        if (!proposalId.equals(challenge.proposalId()) || !conversationId.equals(challenge.conversationId())
                || challenge.status() != ConfirmationChallengeStatus.PENDING) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_FAILED,
                    "challenge binding mismatch");
        }
        String answer = request.challengeAnswer() == null ? "" : request.challengeAnswer().trim();
        if (!properties.getConfirmation().getPermitLiteralAnswers().contains(answer)) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_CHALLENGE_FAILED,
                    "challenge answer rejected");
        }
    }

    private CacheKey challengeKey(String challengeId) {
        return new CacheKey("confirm:challenge:" + challengeId,
                properties.getConfirmation().getChallengeTtl(), CHALLENGE_KEY_NAMESPACE);
    }

    private ConfirmationToken issueToken(PendingProposal proposal) {
        Instant now = clock.instant();
        return tokenService.issue(new TokenClaims(
                action(proposal),
                proposal.conversationId(),
                normalizedPackageId(proposal),
                proposal.payloadHash(),
                proposal.expectedCount() > properties.getConfirmation().getBatchThreshold()
                        ? ConfirmationLevel.BULK
                        : ConfirmationLevel.NORMAL,
                proposal.expectedCount(),
                now,
                now.plus(properties.getConfirmation().getTokenTtl()),
                UUID.randomUUID().toString()));
    }

    private void verifyAndConsume(ConfirmationToken token, PendingProposal proposal) {
        // submit 是真实副作用闸门：创建任务前必须通过 permission 的原子消费与 claims 比对。
        PermissionSubject subject = new PermissionSubject(
                proposal.type() == PendingProposalType.IMAGEGEN ? "submit_imagegen_plan" : "submit_image_plan",
                false,
                action(proposal),
                proposal.conversationId(),
                normalizedPackageId(proposal),
                proposal.payloadHash(),
                proposal.expectedCount(),
                Map.of("proposalId", proposal.proposalId()));
        PermissionContext context = new PermissionContext(
                proposal.conversationId(),
                token,
                null,
                Set.of(),
                Set.of());
        tokenService.verifyAndConsume(token, subject, context, properties.getConfirmation().getBatchThreshold());
    }

    private String createTaskIfPossible(PendingProposal proposal) {
        long packageId = parsePackageId(proposal.packageId());
        if (packageId <= 0L) {
            throw new BusinessException(ConversationErrorCode.PACKAGE_REFERENCE_INVALID,
                    "proposal package id is invalid",
                    Map.of("proposalId", proposal.proposalId(), "packageId", proposal.packageId()));
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

    private static ConfirmationAction action(PendingProposal proposal) {
        return proposal.type() == PendingProposalType.IMAGEGEN
                ? ConfirmationAction.IMAGEGEN
                : ConfirmationAction.SUBMIT_DAG;
    }

    private static ConfirmationSubmitResponse confirmedResponse(PendingProposal proposal) {
        return new ConfirmationSubmitResponse(proposal.proposalId(), proposal.taskId(), "CONFIRMED");
    }

    private static String normalizedPackageId(PendingProposal proposal) {
        return proposal.packageId() == null || proposal.packageId().isBlank() ? "0" : proposal.packageId();
    }

    private static long parsePackageId(String packageId) {
        try {
            return Long.parseLong(packageId);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
