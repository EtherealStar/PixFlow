package com.pixflow.module.dag.propose;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.ir.CanonicalJson;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.CanonicalDag;
import com.pixflow.module.dag.ir.CanonicalDagFactory;
import com.pixflow.module.dag.validate.DagValidationResult;
import com.pixflow.module.dag.validate.DagValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.transaction.annotation.Transactional;

/**
 * PendingPlanService:提案入队 / 状态迁移 / 幂等 / payload_hash 计算。
 *
 * <p>幂等:同 toolCallId 重复调用不产生新 plan(返回原 plan)。
 * 过期:cron 由调用方(模块外)定时调 expireOverdue。
 */
public class PendingPlanService {

    private final PendingPlanMapper mapper;
    private final DagValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DagProperties properties;
    private final CanonicalDagFactory canonicalDagFactory;

    public PendingPlanService(PendingPlanMapper mapper,
                              DagValidator validator,
                              DagProperties properties,
                              ObjectMapper objectMapper,
                              Clock clock,
                              CanonicalDagFactory canonicalDagFactory) {
        this.mapper = mapper;
        this.validator = validator;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.canonicalDagFactory = canonicalDagFactory;
    }

    /**
     * 入队;幂等(同 toolCallId 不重复)。
     *
     * @param toolCallId Agent tool_call_id(用于幂等)
     * @param conversationId 会话 ID
     * @param doc 已浅解析的 DagDocument(本方法内做深校验)
     * @param note 给用户的方案说明(可空)
     * @return PendingPlan(新建或已存在)
     */
    @Transactional
    public PendingPlan enqueue(String toolCallId,
                                String conversationId,
                                DagDocument doc,
                                String note) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 不能为空");
        }
        // 幂等检查
        PendingPlan existing = mapper.findByToolCallId(toolCallId);
        if (existing != null) {
            return existing;
        }
        // 深校验
        DagValidationResult result = validator.validate(doc);
        if (!result.ok()) {
            throw new PixFlowException(DagErrorCode.DAG_INVALID_STRUCTURE,
                "DAG 校验未通过: " + String.join("; ", result.errors()));
        }
        CanonicalDag canonical = canonicalDagFactory.fromDocument(doc, currentSchemaVersion());
        // pending_plan 与 process_task 只保存同一份 canonical JSON，hash 也直接取该事实的 hash。
        String dagJson = new String(canonical.canonicalJson(), StandardCharsets.UTF_8);
        String payloadHash = canonical.canonicalHash();
        // 入库
        PendingPlan plan = new PendingPlan();
        plan.setToolCallId(toolCallId);
        plan.setConversationId(conversationId);
        plan.setType("IMAGE_PLAN");
        plan.setDagJson(dagJson);
        plan.setPayloadHash(payloadHash);
        plan.setSchemaVersion(currentSchemaVersion().raw());
        plan.setNote(note);
        plan.setStatus(PendingPlanStatus.PENDING);
        Instant now = clock.instant();
        plan.setCreatedAt(now);
        plan.setExpiresAt(now.plus(properties.getPendingPlan().getTtl()));
        mapper.insert(plan);
        return plan;
    }

    /**
     * 解析入参 JSON → DagDocument;由 SubmitImagePlanHandler 在调 enqueue 前调用。
     */
    public DagDocument parseDocument(String json) {
        return new DagJsonReader(objectMapper).read(json);
    }

    /**
     * 由 dag handler 反向解析已存 JSON;确认 REST 边界重校验时使用。
     */
    public DagDocument parseStored(String storedJson) {
        return new DagJsonReader(objectMapper).read(storedJson);
    }

    public CanonicalDag revalidate(String storedJson) {
        DagDocument doc = parseStored(storedJson);
        DagValidationResult result = validator.validate(doc);
        if (!result.ok()) {
            throw new PixFlowException(DagErrorCode.DAG_INVALID_STRUCTURE,
                "DAG 重校验未通过: " + String.join("; ", result.errors()));
        }
        return canonicalDagFactory.fromDocument(doc, currentSchemaVersion());
    }

    /**
     * 标记为已确认(确认 REST 边界调用)。仅 PENDING 可转 CONFIRMED。
     *
     * @return 确认后的 plan;若状态不允许(如已 EXPIRED)抛 DAG_PLAN_EXPIRED / DAG_PLAN_ALREADY_CONFIRMED。
     */
    @Transactional
    public PendingPlan confirm(Long planId, String taskId) {
        PendingPlan confirming = startConfirmation(planId);
        return markConfirmedWithTask(confirming.getId(), taskId);
    }

    /**
     * 抢占 proposal 的确认权。只有抢到 CONFIRMING 后，调用方才允许创建 task。
     */
    @Transactional
    public PendingPlan startConfirmation(Long planId) {
        PendingPlan plan = mapper.findById(planId);
        if (plan == null) {
            throw new PixFlowException(DagErrorCode.DAG_PLAN_NOT_FOUND,
                "pending_plan 不存在: id=" + planId);
        }
        if (plan.getStatus() == PendingPlanStatus.EXPIRED) {
            throw new PixFlowException(DagErrorCode.DAG_PLAN_EXPIRED,
                "pending_plan 已过期: id=" + planId);
        }
        if (plan.getStatus() == PendingPlanStatus.CONFIRMED) {
            throw new PixFlowException(DagErrorCode.DAG_PLAN_ALREADY_CONFIRMED,
                "pending_plan 已被确认: id=" + planId);
        }
        if (plan.getStatus() == PendingPlanStatus.CONFIRMING) {
            throw new PixFlowException(DagErrorCode.DAG_PLAN_ALREADY_CONFIRMED,
                "pending_plan 正在确认: id=" + planId);
        }
        Instant now = clock.instant();
        int rows = mapper.updateStatusFrom(planId, PendingPlanStatus.PENDING.name(),
            PendingPlanStatus.CONFIRMING.name(), now);
        if (rows == 0) {
            throw new PixFlowException(DagErrorCode.DAG_PLAN_ALREADY_CONFIRMED,
                "pending_plan 状态竞争: id=" + planId);
        }
        plan.setStatus(PendingPlanStatus.CONFIRMING);
        plan.setConfirmedAt(now);
        return plan;
    }

    @Transactional
    public PendingPlan markConfirmedWithTask(Long planId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Instant now = clock.instant();
        int rows = mapper.markConfirmedWithTask(planId, taskId.trim(), now);
        if (rows == 0) {
            throw new PixFlowException(DagErrorCode.DAG_PLAN_ALREADY_CONFIRMED,
                "pending_plan 状态竞争: id=" + planId);
        }
        PendingPlan plan = mapper.findById(planId);
        plan.setStatus(PendingPlanStatus.CONFIRMED);
        plan.setTaskId(taskId.trim());
        plan.setConfirmedAt(now);
        return plan;
    }

    @Transactional
    public void markConfirmationFailed(Long planId) {
        mapper.updateStatusFrom(planId, PendingPlanStatus.CONFIRMING.name(),
            PendingPlanStatus.PENDING.name(), null);
    }

    /**
     * 把 pending_plan.payload_hash 与重校验后的 DAG hash 比对;不一致即拦截。
     */
    public boolean payloadHashMatches(PendingPlan plan, String revalidatedHash) {
        return plan != null && revalidatedHash != null
            && plan.getPayloadHash().equals(revalidatedHash);
    }

    /** 触发 cron:超龄 PENDING → EXPIRED。返回受影响行数。 */
    @Transactional
    public int expireOverdue() {
        return mapper.expireOverdue();
    }

    /** schema 大版本升级时:把所有 < cutoffMajor 的 PENDING 标 EXPIRED。 */
    @Transactional
    public int expireByOldSchema(String cutoffMajorVersion) {
        return mapper.expireByOldSchema(cutoffMajorVersion);
    }

    private DagSchemaVersion currentSchemaVersion() {
        // 当前所有 schema 都是 1.0;大版本升级时此处与 SchemaRegistryValidator 同步
        return new DagSchemaVersion("1.0");
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
