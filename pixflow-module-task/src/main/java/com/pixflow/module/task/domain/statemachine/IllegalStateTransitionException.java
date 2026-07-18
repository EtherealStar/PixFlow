package com.pixflow.module.task.domain.statemachine;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.domain.error.TaskErrorCode;

public class IllegalStateTransitionException extends PixFlowException {
  public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
    super(
        TaskErrorCode.TASK_STATE_TRANSITION_REJECTED,
        "illegal state transition: " + from + " -> " + to);
  }
}
