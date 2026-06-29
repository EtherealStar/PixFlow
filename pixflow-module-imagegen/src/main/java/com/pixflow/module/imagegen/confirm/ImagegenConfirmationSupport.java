package com.pixflow.module.imagegen.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import com.pixflow.module.imagegen.port.PendingPlanPort;
import com.pixflow.module.imagegen.port.PendingPlanProposal;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * confirm 边界的辅助能力(对齐 imagegen.md §七)。
 *
 * <p>职责:按 planId 取回 proposal → 反序列化为 {@link ImagegenPlan} → 重算 {@code payloadHash}
 * 与 {@code expectedCount}(= 源图张数)。这些值供 {@code module/conversation} 在 confirm REST
 * 边界构造 {@code PermissionSubject} 时调用。
 *
 * <p>本类只读;不消费令牌、不触发 task。token verifyAndConsume 由 permission 在 confirm 边界执行。
 *
 * <p>数据流:
 * <pre>
 * PendingPlanPort.find(planId) → payloadJson → JSON → ImagegenPlan → hasher.hash() + plan.sourceImageIds.size()
 * </pre>
 */
@Component
public class ImagegenConfirmationSupport {

    private static final Logger log = LoggerFactory.getLogger(ImagegenConfirmationSupport.class);

    private final PendingPlanPort pendingPlanPort;
    private final ImagegenPayloadHasher payloadHasher;
    private final ObjectMapper objectMapper;
    private final ImagegenMetrics metrics;

    public ImagegenConfirmationSupport(PendingPlanPort pendingPlanPort,
                                       ImagegenPayloadHasher payloadHasher,
                                       ObjectMapper objectMapper) {
        this(pendingPlanPort, payloadHasher, objectMapper, null);
    }

    @Autowired
    public ImagegenConfirmationSupport(PendingPlanPort pendingPlanPort,
                                       ImagegenPayloadHasher payloadHasher,
                                       ObjectMapper objectMapper,
                                       ImagegenMetrics metrics) {
        this.pendingPlanPort = pendingPlanPort;
        this.payloadHasher = payloadHasher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 重算 planId 对应提案的 payloadHash。
     *
     * @param planId pending plan ID
     * @return SHA-256 hex(64 字符)
     * @throws PixFlowException(IMAGEGEN_PLAN_NOT_FOUND) 取回提案落空
     */
    public String payloadHash(String planId) {
        ImagegenPlan plan = loadPlan(planId);
        return payloadHasher.hash(plan);
    }

    /**
     * 期望生成式单元数(= 源图张数,与 imagegen.md §8.1「1 源图 → 1 重绘」对齐)。
     */
    public int expectedCount(String planId) {
        ImagegenPlan plan = loadPlan(planId);
        return plan.sourceImageIds().size();
    }

    /**
     * 校验 token claims 中的 payloadHash 与 confirm 时重算结果一致。
     * 不一致 → 抛 {@code IMAGEGEN_PAYLOAD_HASH_MISMATCH} 并递增指标 {@code payload.hash.mismatch}。
     */
    public void verifyHash(String planId, String claimsPayloadHash) {
        String current = payloadHash(planId);
        if (claimsPayloadHash == null || !current.equals(claimsPayloadHash)) {
            if (metrics != null) {
                metrics.recordPayloadHashMismatch();
            }
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PAYLOAD_HASH_MISMATCH,
                "payloadHash 不一致: planId=" + planId);
        }
    }

    /** 内部:取回提案 + 反序列化为 {@link ImagegenPlan}。 */
    private ImagegenPlan loadPlan(String planId) {
        PendingPlanProposal proposal = pendingPlanPort.find(planId)
            .orElseThrow(() -> new PixFlowException(ImagegenErrorCode.IMAGEGEN_PLAN_NOT_FOUND,
                "pending_plan 不存在: id=" + planId));
        try {
            return objectMapper.readValue(proposal.payloadJson(), ImagegenPlan.class);
        } catch (Exception e) {
            // 反序列化失败 → 视为提案落空(payload 损坏),UI 提示「提案不可读」
            log.warn("反序列化提案失败: planId={}", planId, e);
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PLAN_NOT_FOUND,
                "提案载荷不可读: planId=" + planId, e);
        }
    }
}