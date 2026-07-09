package com.pixflow.module.dag.propose;

import com.pixflow.contracts.proposal.PendingPlanPort;
import com.pixflow.contracts.proposal.PendingPlanProposal;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.ir.DagDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * pending_plan 的共享提案端口实现。
 *
 * <p>DAG 提案仍走 {@link PendingPlanService} 的深校验路径;imagegen 提案只保存中立
 * payload,具体业务校验由 imagegen handler 与确认支撑负责。
 */
public class PendingPlanPortAdapter implements PendingPlanPort {
    private static final String TYPE_IMAGE_PLAN = "IMAGE_PLAN";
    private static final String TYPE_IMAGEGEN = "IMAGEGEN";

    private final PendingPlanMapper pendingPlanMapper;
    private final PendingPlanService pendingPlanService;
    private final DagProperties properties;

    public PendingPlanPortAdapter(PendingPlanMapper pendingPlanMapper,
                                  PendingPlanService pendingPlanService,
                                  DagProperties properties) {
        this.pendingPlanMapper = pendingPlanMapper;
        this.pendingPlanService = pendingPlanService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public String enqueue(PendingPlanProposal proposal) {
        if (isImagePlan(proposal.planType())) {
            DagDocument document = pendingPlanService.parseDocument(proposal.payloadJson());
            PendingPlan plan = pendingPlanService.enqueue(
                    proposal.toolCallId(),
                    proposal.conversationId(),
                    document,
                    packageNote(proposal.packageId()));
            return String.valueOf(plan.getId());
        }

        PendingPlan existing = pendingPlanMapper.findByToolCallId(proposal.toolCallId());
        if (existing != null) {
            return String.valueOf(existing.getId());
        }

        PendingPlan plan = new PendingPlan();
        plan.setToolCallId(proposal.toolCallId());
        plan.setConversationId(proposal.conversationId());
        plan.setType(normalizeType(proposal.planType()));
        plan.setDagJson(proposal.payloadJson());
        plan.setPayloadHash(sha256(proposal.payloadJson()));
        plan.setSchemaVersion("1");
        plan.setNote(packageNote(proposal.packageId()));
        plan.setStatus(PendingPlanStatus.PENDING);
        plan.setCreatedAt(proposal.createdAt());
        plan.setExpiresAt(proposal.createdAt().plus(properties.getPendingPlan().getTtl()));
        pendingPlanMapper.insert(plan);
        return String.valueOf(plan.getId());
    }

    @Override
    public Optional<PendingPlanProposal> find(String planId) {
        Long id = parsePlanId(planId);
        if (id == null) {
            return Optional.empty();
        }
        PendingPlan plan = pendingPlanMapper.findById(id);
        if (plan == null) {
            return Optional.empty();
        }
        return Optional.of(new PendingPlanProposal(
                plan.getType(),
                plan.getDagJson(),
                plan.getConversationId(),
                parsePackageId(plan.getNote()),
                plan.getToolCallId(),
                plan.getCreatedAt()));
    }

    private static boolean isImagePlan(String planType) {
        return "IMAGE_DAG".equalsIgnoreCase(planType) || TYPE_IMAGE_PLAN.equalsIgnoreCase(planType);
    }

    private static String normalizeType(String planType) {
        if (isImagePlan(planType)) {
            return TYPE_IMAGE_PLAN;
        }
        if (TYPE_IMAGEGEN.equalsIgnoreCase(planType)) {
            return TYPE_IMAGEGEN;
        }
        return planType;
    }

    private static String packageNote(String packageId) {
        return packageId == null || packageId.isBlank() ? null : "packageId=" + packageId;
    }

    private static String parsePackageId(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        for (String part : note.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("packageId=")) {
                return trimmed.substring("packageId=".length());
            }
        }
        return null;
    }

    private static Long parsePlanId(String planId) {
        try {
            return Long.parseLong(planId);
        } catch (RuntimeException ex) {
            return null;
        }
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
