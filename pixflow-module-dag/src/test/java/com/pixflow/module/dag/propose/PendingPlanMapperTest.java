package com.pixflow.module.dag.propose;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PendingPlan 实体基本行为测试(getter/setter)。
 */
class PendingPlanMapperTest {

    @org.junit.jupiter.api.Test
    void pendingPlan_settersAndGetters_work() {
        PendingPlan p = new PendingPlan();
        p.setId(1L);
        p.setToolCallId("tc1");
        p.setConversationId("conv1");
        p.setType("IMAGE_PLAN");
        p.setDagJson("{}");
        p.setPayloadHash("hash");
        p.setSchemaVersion("1.0");
        p.setNote("n");
        p.setStatus(PendingPlanStatus.PENDING);
        java.time.Instant now = java.time.Instant.now();
        p.setCreatedAt(now);
        p.setExpiresAt(now.plusSeconds(1800));
        p.setConfirmedAt(now);
        p.setTaskId("t1");

        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getToolCallId()).isEqualTo("tc1");
        assertThat(p.getStatus()).isEqualTo(PendingPlanStatus.PENDING);
        assertThat(p.getTaskId()).isEqualTo("t1");
        assertThat(p.getNote()).isEqualTo("n");
    }

    @org.junit.jupiter.api.Test
    void pendingPlanStatus_enumValues() {
        assertThat(PendingPlanStatus.values()).hasSize(4);
        assertThat(PendingPlanStatus.valueOf("PENDING")).isEqualTo(PendingPlanStatus.PENDING);
    }
}