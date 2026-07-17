package com.pixflow.module.imagegen.proposal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.proposal.ProposalDraft;
import com.pixflow.contracts.proposal.ProposalPublicationPort;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import com.pixflow.harness.tools.ProposalPublicationAuthorizer;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 提案入队服务(对齐 imagegen.md §5.2 / §六)。
 *
 * <p>流程:
 * <ol>
 *   <li>序列化 {@link ImagegenPlan} 为 payload JSON</li>
 *   <li>调 {@link ImagegenPayloadHasher} 计算 payloadHash</li>
 *   <li>调 {@link ProposalPublicationPort#publish} 发布(同 toolCallId 幂等)</li>
 *   <li>记录 metrics(proposal result=ok / reject)</li>
 *   <li>返回 planId</li>
 * </ol>
 *
 * <p>同 toolCallId 重复发布由 {@link ProposalPublicationPort} 保证幂等；本类不感知进程内实现。
 */
@Service
public class ImagegenPlanService {

    private static final Logger log = LoggerFactory.getLogger(ImagegenPlanService.class);

    /** planType 标识:与 dag 的 "IMAGE_DAG" 区分,持久化时识别载荷类型。 */
    public static final String PLAN_TYPE_IMAGEGEN = "IMAGEGEN";

    private final ImagegenPlanValidator validator;
    private final ProposalPublicationPort publicationPort;
    private final ImagegenPayloadHasher payloadHasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ImagegenMetrics metrics;

    public ImagegenPlanService(ImagegenPlanValidator validator,
                               ProposalPublicationPort publicationPort,
                               ImagegenPayloadHasher payloadHasher,
                               ObjectMapper objectMapper) {
        this(validator, publicationPort, payloadHasher, objectMapper,
            Clock.systemUTC(), null);
    }

    @Autowired
    public ImagegenPlanService(ImagegenPlanValidator validator,
                               ProposalPublicationPort publicationPort,
                               ImagegenPayloadHasher payloadHasher,
                               ObjectMapper objectMapper,
                               Clock clock,
                               ImagegenMetrics metrics) {
        this.validator = validator;
        this.publicationPort = publicationPort;
        this.payloadHasher = payloadHasher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * 提案入队;返回 planId。
     *
     * @param inputs  工具入参(浅层)
     * @param toolCallId  Agent 工具调用 ID(幂等键)
     * @param conversationId 会话 ID
     * @param packageId    素材包 ID
     * @return planId
     * @throws PixFlowException 校验失败(转 tool error)/ 入队失败
     */
    public String enqueue(ImagegenPlanInputs inputs,
                          String toolCallId,
                          String conversationId,
                          String packageId,
                          ProposalPublicationAuthorizer authorizer) {
        Timer.Sample sample = metrics == null ? null : metrics.startProposal();
        try {
            // 1. 深校验 + 规范化 → ImagegenPlan
            ImagegenPlan plan = validator.validate(inputs, conversationId, packageId);

            // 2. 序列化
            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(plan);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("序列化 ImagegenPlan 失败", e);
            }

            // 3. 入队(幂等:同 toolCallId 不产生新 plan)
            String payloadHash = payloadHasher.hash(plan);
            java.util.List<String> referenceKeys = plan.sourceImageIds().stream()
                    .map(imageId -> "package:" + packageId + "/image:" + imageId)
                    .toList();
            ProposalDraft proposal = new ProposalDraft(
                PLAN_TYPE_IMAGEGEN,
                payloadJson,
                conversationId,
                packageId,
                toolCallId,
                payloadHash,
                plan.sourceImageIds().size(),
                referenceKeys,
                clock.instant());
            // 深校验与稳定 hash 完成后再授权，避免用未经验证的模型输入形成权限事实。
            authorizer.authorize(PLAN_TYPE_IMAGEGEN, referenceKeys, payloadHash);
            String planId = publicationPort.publish(proposal);

            if (metrics != null) {
                metrics.recordProposal("ok", null);
            }
            log.info("imagegen plan enqueued: planId={}, conversationId={}, sourceCount={}, toolCallId={}",
                planId, conversationId, plan.sourceImageIds().size(), toolCallId);
            return planId;
        } catch (PixFlowException pe) {
            if (metrics != null) {
                metrics.recordProposal("reject", pe.code() instanceof ImagegenErrorCode ige
                    ? ige
                    : null);
            }
            throw pe;
        } finally {
            if (sample != null && metrics != null) {
                metrics.stopProposal(sample, true);
            }
        }
    }

    /** 取回 plan 的 payloadHash(供工具返回字段直接透出,避免 UI 二次重算)。 */
    public String payloadHashFor(ImagegenPlan plan) {
        return payloadHasher.hash(plan);
    }

    /** 暴露给上层:Clock 注入便于测试时间相关字段。 */
    public Instant currentInstant() {
        return clock.instant();
    }
}
