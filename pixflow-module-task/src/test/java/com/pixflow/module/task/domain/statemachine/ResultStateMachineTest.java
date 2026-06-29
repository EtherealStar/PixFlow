package com.pixflow.module.task.domain.statemachine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.task.domain.model.ResultStatus;
import org.junit.jupiter.api.Test;

class ResultStateMachineTest {
    @Test
    void pendingCanMoveToAllTerminalStates() {
        assertThatCode(() -> ResultStateMachine.INSTANCE.verify(ResultStatus.PENDING, ResultStatus.SUCCESS))
                .doesNotThrowAnyException();
        assertThatCode(() -> ResultStateMachine.INSTANCE.verify(ResultStatus.PENDING, ResultStatus.FAILED))
                .doesNotThrowAnyException();
        assertThatCode(() -> ResultStateMachine.INSTANCE.verify(ResultStatus.PENDING, ResultStatus.SKIPPED))
                .doesNotThrowAnyException();
    }

    @Test
    void successIsTerminal() {
        assertThatThrownBy(() -> ResultStateMachine.INSTANCE.verify(ResultStatus.SUCCESS, ResultStatus.FAILED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
