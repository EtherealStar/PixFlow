package com.pixflow.module.conversation.proposal;

import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.ValidatedDag;
import com.pixflow.module.dag.propose.PendingPlan;
import com.pixflow.module.dag.propose.PendingPlanMapper;
import com.pixflow.module.dag.propose.PendingPlanService;
import com.pixflow.module.file.pkg.ImageReference;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import com.pixflow.module.imagegen.confirm.ImagegenConfirmationSupport;
import java.util.List;
import java.util.Optional;

public class PendingProposalRepository {
    private final PendingPlanMapper pendingPlanMapper;
    private final PendingPlanService pendingPlanService;
    private final PackageReferenceResolver packageReferenceResolver;
    private final BranchExpander branchExpander;
    private final ImagegenConfirmationSupport imagegenConfirmationSupport;

    public PendingProposalRepository(
            PendingPlanMapper pendingPlanMapper,
            PendingPlanService pendingPlanService,
            PackageReferenceResolver packageReferenceResolver,
            BranchExpander branchExpander,
            ImagegenConfirmationSupport imagegenConfirmationSupport) {
        this.pendingPlanMapper = pendingPlanMapper;
        this.pendingPlanService = pendingPlanService;
        this.packageReferenceResolver = packageReferenceResolver;
        this.branchExpander = branchExpander;
        this.imagegenConfirmationSupport = imagegenConfirmationSupport;
    }

    public Optional<PendingProposal> find(String proposalId) {
        Long id = parseId(proposalId);
        PendingPlan plan = pendingPlanMapper.findById(id);
        return plan == null ? Optional.empty() : Optional.of(toProposal(plan));
    }

    public PendingProposal require(String proposalId) {
        return find(proposalId)
                .orElseThrow(() -> new BusinessException(ConversationErrorCode.PROPOSAL_NOT_FOUND,
                        "proposal not found: " + proposalId));
    }

    /**
     * 标记 proposal 为已确认,委托给 dag 模块 {@link PendingPlanService#confirm(Long, String)},
     * 由后者统一提供事务 + CAS 保护(数据库 {@code AND status = 'PENDING'} 谓词)。
     *
     * <p>并发调用时只有一个调用方会成功,其余会触发 {@link com.pixflow.module.dag.error.DagErrorCode#DAG_PLAN_ALREADY_CONFIRMED},
     * 由 caller 决定是回 409 还是按幂等路径返回既有 taskId。
     */
    public PendingProposal markConfirmed(PendingProposal proposal, String taskId) {
        PendingPlan confirmed = pendingPlanService.confirm(Long.parseLong(proposal.proposalId()), taskId);
        return PendingProposal.from(confirmed,
                proposal.payloadHash() == null ? confirmed.getPayloadHash() : proposal.payloadHash(),
                proposal.expectedCount());
    }

    public PendingProposal startConfirmation(PendingProposal proposal) {
        PendingPlan confirming = pendingPlanService.startConfirmation(Long.parseLong(proposal.proposalId()));
        return PendingProposal.from(confirming,
                proposal.payloadHash() == null ? confirming.getPayloadHash() : proposal.payloadHash(),
                proposal.expectedCount());
    }

    public PendingProposal markConfirmedWithTask(PendingProposal proposal, String taskId) {
        PendingPlan confirmed = pendingPlanService.markConfirmedWithTask(Long.parseLong(proposal.proposalId()), taskId);
        return PendingProposal.from(confirmed,
                proposal.payloadHash() == null ? confirmed.getPayloadHash() : proposal.payloadHash(),
                proposal.expectedCount());
    }

    public void markConfirmationFailed(PendingProposal proposal) {
        pendingPlanService.markConfirmationFailed(Long.parseLong(proposal.proposalId()));
    }

    private PendingProposal toProposal(PendingPlan plan) {
        PendingProposal base = PendingProposal.from(plan);
        if (base.type() == PendingProposalType.IMAGEGEN) {
            return imagegenFacts(plan, base);
        }
        return dagFacts(plan, base);
    }

    private PendingProposal imagegenFacts(PendingPlan plan, PendingProposal base) {
        if (imagegenConfirmationSupport == null) {
            return base;
        }
        String planId = String.valueOf(plan.getId());
        return PendingProposal.from(
                plan,
                imagegenConfirmationSupport.payloadHash(planId),
                imagegenConfirmationSupport.expectedCount(planId));
    }

    private PendingProposal dagFacts(PendingPlan plan, PendingProposal base) {
        long packageId = parsePackageId(base.packageId());
        if (packageId <= 0L) {
            return base;
        }
        ValidatedDag dag = pendingPlanService.revalidate(plan.getDagJson());
        List<ImageDescriptor> images = packageReferenceResolver.listImages(base.packageId()).stream()
                .map(PendingProposalRepository::toImageDescriptor)
                .toList();
        // DAG 真实执行数量必须由当前提案和素材包事实重算，不能信任 note 或前端传入值。
        int expectedCount = branchExpander.expand(dag, images).size();
        return PendingProposal.from(plan, plan.getPayloadHash(), expectedCount);
    }

    private static ImageDescriptor toImageDescriptor(ImageReference image) {
        return new ImageDescriptor(
                image.imageId(),
                image.skuId(),
                image.groupKey(),
                image.viewId(),
                image.objectKey(),
                null);
    }

    private static Long parseId(String proposalId) {
        try {
            return Long.parseLong(proposalId);
        } catch (RuntimeException ex) {
            throw new BusinessException(ConversationErrorCode.PROPOSAL_NOT_FOUND,
                    "proposal not found: " + proposalId);
        }
    }

    private static long parsePackageId(String packageId) {
        try {
            return Long.parseLong(packageId);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
