package com.pixflow.harness.state.query;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.model.ExecutionStateSnapshot;
import com.pixflow.harness.state.model.ProgressView;
import com.pixflow.harness.state.observability.StateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort;
import com.pixflow.harness.state.runtime.CancellationReader;
import com.pixflow.harness.state.runtime.ProgressReader;
import java.time.Clock;
import java.time.Instant;

public class DefaultExecutionStateService implements ExecutionStateService {
    private final CheckpointReadPort checkpointReadPort;
    private final ProgressReader progressReader;
    private final CancellationReader cancellationReader;
    private final StateMetrics metrics;
    private final Clock clock;

    public DefaultExecutionStateService(
            CheckpointReadPort checkpointReadPort,
            ProgressReader progressReader,
            CancellationReader cancellationReader,
            StateMetrics metrics,
            Clock clock) {
        this.checkpointReadPort = checkpointReadPort;
        this.progressReader = progressReader;
        this.cancellationReader = cancellationReader;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public ExecutionStateSnapshot snapshot(String taskId) {
        long started = System.nanoTime();
        try {
            var status = checkpointReadPort.loadTaskStatus(taskId)
                    .orElseThrow(() -> new PixFlowException(StateErrorCode.STATE_TASK_NOT_FOUND, "Task not found: " + taskId));
            ProgressView progress = progressReader.read(taskId);
            boolean cancelRequested = cancellationReader.isCancelRequested(taskId);
            ExecutionStateSnapshot snapshot = new ExecutionStateSnapshot(
                    taskId,
                    status,
                    progress,
                    cancelRequested,
                    Instant.now(clock));
            metrics.recordSnapshot("ok", System.nanoTime() - started);
            return snapshot;
        } catch (RuntimeException ex) {
            metrics.recordSnapshot("error", System.nanoTime() - started);
            throw ex;
        }
    }
}
