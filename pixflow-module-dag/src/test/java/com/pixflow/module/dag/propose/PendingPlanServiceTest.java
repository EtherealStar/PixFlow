package com.pixflow.module.dag.propose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.CanonicalJson;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * PendingPlanService 单测:幂等 + payload_hash 稳定 + 状态迁移。
 *
 * <p>mapper 用 Mockito mock;时钟固定便于断言 expires_at。
 */
class PendingPlanServiceTest {

    private PendingPlanMapper mapper;
    private DagValidator validator;
    private DagProperties properties;
    private ObjectMapper objectMapper;
    private Clock clock;
    private PendingPlanService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PendingPlanMapper.class);
        validator = new DagValidator(new ParamSchemaRegistry(), 50, 1);
        properties = new DagProperties();
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        service = new PendingPlanService(mapper, validator, properties, objectMapper, clock,
                new com.pixflow.module.dag.ir.CanonicalDagFactory(objectMapper));
    }

    private String validDagJson() {
        return "{\"nodes\":[{\"id\":\"n1\",\"tool\":\"resize\",\"params\":{\"width\":800}}],\"edges\":[]}";
    }

    @Test
    void enqueue_insertsPlan_withComputedPayloadHash() {
        when(mapper.findByToolCallId("tc1")).thenReturn(null);
        when(mapper.insert(any(PendingPlan.class))).thenAnswer(inv -> {
            PendingPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        });
        DagDocument doc = service.parseDocument(validDagJson());
        PendingPlan plan = service.enqueue("tc1", "conv1", doc, "note");
        assertThat(plan.getId()).isEqualTo(1L);
        assertThat(plan.getStatus()).isEqualTo(PendingPlanStatus.PENDING);
        assertThat(plan.getSchemaVersion()).isEqualTo("1.0");
        assertThat(plan.getType()).isEqualTo("IMAGE_PLAN");
        assertThat(plan.getConversationId()).isEqualTo("conv1");
        // dagJson 已经是持久化 canonical bytes，hash 必须与这份唯一事实一致。
        String expectedHash = sha256(plan.getDagJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(plan.getPayloadHash()).isEqualTo(expectedHash);
        assertThat(plan.getExpiresAt()).isEqualTo(
            Instant.parse("2026-06-29T00:00:00Z").plus(properties.getPendingPlan().getTtl()));
    }

    @Test
    void enqueue_isIdempotent_forSameToolCallId() {
        PendingPlan existing = new PendingPlan();
        existing.setId(99L);
        existing.setStatus(PendingPlanStatus.PENDING);
        existing.setToolCallId("tc1");
        when(mapper.findByToolCallId("tc1")).thenReturn(existing);
        DagDocument doc = service.parseDocument(validDagJson());
        PendingPlan plan = service.enqueue("tc1", "conv1", doc, null);
        assertThat(plan.getId()).isEqualTo(99L);
        verify(mapper, times(0)).insert(any(PendingPlan.class));
    }

    @Test
    void enqueue_throwsPixFlowException_onInvalidDag() {
        when(mapper.findByToolCallId("tc1")).thenReturn(null);
        String invalidJson = "{\"nodes\":[],\"edges\":[]}"; // 节点数 < min
        DagDocument doc = service.parseDocument(invalidJson);
        assertThatThrownBy(() -> service.enqueue("tc1", "conv1", doc, null))
            .isInstanceOf(PixFlowException.class)
            .hasMessageContaining("DAG 校验未通过");
    }

    @Test
    void enqueue_throwsIllegalArgument_whenToolCallIdBlank() {
        DagDocument doc = service.parseDocument(validDagJson());
        assertThatThrownBy(() -> service.enqueue("", "conv1", doc, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_throws_whenPlanNotFound() {
        when(mapper.findById(123L)).thenReturn(null);
        assertThatThrownBy(() -> service.confirm(123L, "task1"))
            .isInstanceOf(PixFlowException.class)
            .hasFieldOrPropertyWithValue("code", DagErrorCode.DAG_PLAN_NOT_FOUND);
    }

    @Test
    void confirm_throws_whenPlanExpired() {
        PendingPlan plan = new PendingPlan();
        plan.setId(1L);
        plan.setStatus(PendingPlanStatus.EXPIRED);
        when(mapper.findById(1L)).thenReturn(plan);
        assertThatThrownBy(() -> service.confirm(1L, "task1"))
            .isInstanceOf(PixFlowException.class)
            .hasFieldOrPropertyWithValue("code", DagErrorCode.DAG_PLAN_EXPIRED);
    }

    @Test
    void confirm_throws_whenAlreadyConfirmed() {
        PendingPlan plan = new PendingPlan();
        plan.setId(1L);
        plan.setStatus(PendingPlanStatus.CONFIRMED);
        when(mapper.findById(1L)).thenReturn(plan);
        assertThatThrownBy(() -> service.confirm(1L, "task1"))
            .isInstanceOf(PixFlowException.class)
            .hasFieldOrPropertyWithValue("code", DagErrorCode.DAG_PLAN_ALREADY_CONFIRMED);
    }

    @Test
    void confirm_marksPending_asConfirmed() {
        PendingPlan plan = new PendingPlan();
        plan.setId(1L);
        plan.setStatus(PendingPlanStatus.PENDING);
        when(mapper.findById(1L)).thenReturn(plan);
        when(mapper.updateStatusFrom(1L, "PENDING", "CONFIRMING",
            Instant.parse("2026-06-29T00:00:00Z"))).thenReturn(1);
        when(mapper.markConfirmedWithTask(1L, "task1",
            Instant.parse("2026-06-29T00:00:00Z"))).thenReturn(1);
        PendingPlan result = service.confirm(1L, "task1");
        assertThat(result.getStatus()).isEqualTo(PendingPlanStatus.CONFIRMED);
        assertThat(result.getTaskId()).isEqualTo("task1");
    }

    @Test
    void payloadHashMatches_returnsTrue_whenHashesEqual() {
        PendingPlan plan = new PendingPlan();
        plan.setPayloadHash("abc");
        assertThat(service.payloadHashMatches(plan, "abc")).isTrue();
        assertThat(service.payloadHashMatches(plan, "xyz")).isFalse();
        assertThat(service.payloadHashMatches(null, "abc")).isFalse();
    }

    @Test
    void parseDocument_parsesValidDag() {
        DagDocument doc = service.parseDocument(validDagJson());
        assertThat(doc.nodes()).hasSize(1);
    }

    @Test
    void revalidate_throws_onInvalidDag() {
        assertThatThrownBy(() -> service.revalidate("not-json"))
            .isInstanceOf(Exception.class);
    }

    @Test
    void expireOverdue_callsMapper() {
        when(mapper.expireOverdue()).thenReturn(5);
        int n = service.expireOverdue();
        assertThat(n).isEqualTo(5);
        verify(mapper).expireOverdue();
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
