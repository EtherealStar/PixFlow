package com.pixflow.module.conversation.proposal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.dag.propose.PendingPlan;
import com.pixflow.module.dag.propose.PendingPlanMapper;
import com.pixflow.module.dag.propose.PendingPlanStatus;
import com.pixflow.module.imagegen.port.PendingPlanPort;
import com.pixflow.module.imagegen.port.PendingPlanProposal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

public class PendingPlanPortAdapter implements PendingPlanPort {
    private final PendingPlanMapper pendingPlanMapper;

    public PendingPlanPortAdapter(PendingPlanMapper pendingPlanMapper) {
        this.pendingPlanMapper = pendingPlanMapper;
    }

    @Override
    public String enqueue(PendingPlanProposal proposal) {
        PendingPlan existing = pendingPlanMapper.findByToolCallId(proposal.toolCallId());
        if (existing != null) {
            return String.valueOf(existing.getId());
        }
        PendingPlan plan = new PendingPlan();
        plan.setToolCallId(proposal.toolCallId());
        plan.setConversationId(proposal.conversationId());
        plan.setType(proposal.planType());
        plan.setDagJson(proposal.payloadJson());
        plan.setPayloadHash(sha256(proposal.payloadJson()));
        plan.setSchemaVersion("1");
        plan.setNote(proposal.packageId() == null ? null : "packageId=" + proposal.packageId());
        plan.setStatus(PendingPlanStatus.PENDING);
        plan.setCreatedAt(proposal.createdAt());
        plan.setExpiresAt(proposal.createdAt().plus(Duration.ofHours(1)));
        pendingPlanMapper.insert(plan);
        return String.valueOf(plan.getId());
    }

    @Override
    public Optional<PendingPlanProposal> find(String planId) {
        PendingPlan plan = pendingPlanMapper.selectOne(new LambdaQueryWrapper<PendingPlan>()
                .eq(PendingPlan::getId, Long.parseLong(planId)));
        if (plan == null) {
            return Optional.empty();
        }
        return Optional.of(new PendingPlanProposal(
                plan.getType(),
                plan.getDagJson(),
                plan.getConversationId(),
                PendingProposal.from(plan).packageId(),
                plan.getToolCallId(),
                plan.getCreatedAt()));
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
