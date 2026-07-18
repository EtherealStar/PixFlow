package com.pixflow.module.task.domain.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.task.domain.model.TaskStatus;
import org.junit.jupiter.api.Test;

class TaskStateMachineTest {
  @Test
  void validTransitionsPass() {
    assertThatCode(() -> TaskStateMachine.INSTANCE.verify(TaskStatus.PENDING, TaskStatus.QUEUED))
        .doesNotThrowAnyException();
    assertThatCode(() -> TaskStateMachine.INSTANCE.verify(TaskStatus.QUEUED, TaskStatus.RUNNING))
        .doesNotThrowAnyException();
    assertThatCode(() -> TaskStateMachine.INSTANCE.verify(TaskStatus.RUNNING, TaskStatus.PARTIAL))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidTransitionsThrow() {
    assertThat(TaskStateMachine.INSTANCE.canTransit(TaskStatus.COMPLETED, TaskStatus.RUNNING))
        .isFalse();
    assertThatThrownBy(
            () -> TaskStateMachine.INSTANCE.verify(TaskStatus.COMPLETED, TaskStatus.RUNNING))
        .isInstanceOf(IllegalStateTransitionException.class)
        .hasMessageContaining("COMPLETED")
        .hasMessageContaining("RUNNING");
  }
}
