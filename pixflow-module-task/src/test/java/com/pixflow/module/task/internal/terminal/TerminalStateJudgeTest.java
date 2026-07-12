package com.pixflow.module.task.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.task.domain.model.TaskStatus;
import org.junit.jupiter.api.Test;

class TerminalStateJudgeTest {
    @Test
    void appliesCanonicalTerminalAggregation() {
        assertThat(TerminalStateJudge.decide(2, 2, 0, 0)).isEqualTo(TaskStatus.COMPLETED);
        assertThat(TerminalStateJudge.decide(2, 1, 1, 0)).isEqualTo(TaskStatus.PARTIAL);
        assertThat(TerminalStateJudge.decide(2, 0, 2, 0)).isEqualTo(TaskStatus.FAILED);
        assertThat(TerminalStateJudge.decide(2, 0, 0, 2)).isEqualTo(TaskStatus.CANCELLED);
    }
}
