package com.pixflow.module.imagegen.proposal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.metrics.ImagegenMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 单图 redraw 深校验服务，不负责 Proposal id 或发布。
 *
 * <p>本服务只返回稳定 payload/hash；App 负责授权并交给 Conversation 发布。
 */
@Service
public class ImagegenPlanService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImagegenPlanService.class);

    /** planType 标识:与 dag 的 "IMAGE_DAG" 区分,持久化时识别载荷类型。 */
    public static final String PLAN_TYPE_IMAGEGEN = "IMAGEGEN";

    private final ImagegenPlanValidator validator;

    private final ImagegenPayloadHasher payloadHasher;

    private final ObjectMapper objectMapper;

    private final ImagegenMetrics metrics;

    public ImagegenPlanService(ImagegenPlanValidator validator,
                               ImagegenPayloadHasher payloadHasher,
                               ObjectMapper objectMapper) {
        this(validator, payloadHasher, objectMapper, null);
    }

    @Autowired
    public ImagegenPlanService(ImagegenPlanValidator validator,
                               ImagegenPayloadHasher payloadHasher,
                               ObjectMapper objectMapper,
                               ImagegenMetrics metrics) {
        this.validator = validator;
        this.payloadHasher = payloadHasher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 深校验并生成不可变的 redraw payload。
     */
    public ValidatedRedrawRequest validate(
            ImagegenPlanInputs inputs, String conversationId) {
        Timer.Sample sample = metrics == null ? null : metrics.startProposal();
        try {
            // 1. 深校验 + 规范化 → ImagegenPlan
            ImagegenPlan plan = validator.validate(inputs, conversationId);

            // 2. 序列化
            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(plan);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("序列化 ImagegenPlan 失败", e);
            }

            String payloadHash = payloadHasher.hash(plan);
            ImageAssetReferenceKey source = plan.sourceReference();

            if (metrics != null) {
                metrics.recordProposal("ok", null);
            }
            LOGGER.info("imagegen redraw validated: conversationId={}, source={}",
                    conversationId, plan.sourceReferenceKey());
            return new ValidatedRedrawRequest(source, payloadJson, payloadHash);
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

}
