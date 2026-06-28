package com.pixflow.harness.state.recovery;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.model.CompletedUnits;
import com.pixflow.harness.state.observability.StateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort;

public class DefaultRecoveryCoordinator implements RecoveryCoordinator {
    private final CheckpointReadPort checkpointReadPort;
    private final StateMetrics metrics;

    public DefaultRecoveryCoordinator(CheckpointReadPort checkpointReadPort, StateMetrics metrics) {
        this.checkpointReadPort = checkpointReadPort;
        this.metrics = metrics;
    }

    @Override
    public CompletedUnits resolveSkippable(String taskId) {
        CompletedUnits completed = checkpointReadPort.loadCompletedUnits(taskId)
                .orElseThrow(() -> new PixFlowException(StateErrorCode.STATE_TASK_NOT_FOUND, "Task not found: " + taskId));
        metrics.recordSkippableUnits(completed.size());
        return completed;
    }
}
