package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link TransitionReason} 枚举覆盖：6 态完整、命名稳定。
 */
class TransitionReasonTest {

    @Test
    void allSixReasonsArePresent() {
        assertThat(TransitionReason.values()).hasSize(6);
        assertThat(TransitionReason.valueOf("TOOL_USE")).isNotNull();
        assertThat(TransitionReason.valueOf("COMPLETED")).isNotNull();
        assertThat(TransitionReason.valueOf("RATE_LIMIT_RETRY")).isNotNull();
        assertThat(TransitionReason.valueOf("REACTIVE_COMPACT_RETRY")).isNotNull();
        assertThat(TransitionReason.valueOf("MAX_OUTPUT_TOKENS_ESCALATE")).isNotNull();
        assertThat(TransitionReason.valueOf("MAX_OUTPUT_TOKENS_RECOVERY")).isNotNull();
    }
}