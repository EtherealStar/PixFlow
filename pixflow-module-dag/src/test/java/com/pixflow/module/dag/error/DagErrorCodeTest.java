package com.pixflow.module.dag.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DagErrorCode 枚举与 category 一致性测试。
 */
class DagErrorCodeTest {

    @Test
    void allValidationCodesHaveValidationCategory() {
        assertThat(DagErrorCode.DAG_INVALID_STRUCTURE.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
        assertThat(DagErrorCode.DAG_NODE_LIMIT_EXCEEDED.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
        assertThat(DagErrorCode.DAG_UNKNOWN_TOOL.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
        assertThat(DagErrorCode.DAG_INVALID_PARAMS.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
        assertThat(DagErrorCode.DAG_HAS_CYCLE.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
        assertThat(DagErrorCode.DAG_INVALID_GROUP_BRANCH.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
        assertThat(DagErrorCode.DAG_INVALID_OP_ORDER.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.VALIDATION);
    }

    @Test
    void codesAreUnique() {
        DagErrorCode[] codes = DagErrorCode.values();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DagErrorCode c : codes) {
            assertThat(seen.add(c.code())).as("code 重复: " + c.code()).isTrue();
        }
    }

    @Test
    void everyCodeHasCategory() {
        for (DagErrorCode c : DagErrorCode.values()) {
            assertThat(c.category()).as(c.name() + " 缺 category").isNotNull();
        }
    }

    @Test
    void timeoutCodeHasDependencyCategory() {
        assertThat(DagErrorCode.DAG_UNIT_TIMEOUT.category())
            .isEqualTo(com.pixflow.common.error.ErrorCategory.DEPENDENCY);
    }
}