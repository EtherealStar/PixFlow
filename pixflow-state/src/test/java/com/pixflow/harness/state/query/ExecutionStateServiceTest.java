package com.pixflow.harness.state.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.config.StateProperties;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.model.SkippableWorkUnits;
import com.pixflow.harness.state.model.ProgressSource;
import com.pixflow.harness.state.model.TaskRunStatus;
import com.pixflow.harness.state.observability.NoopStateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort.PersistedCounts;
import com.pixflow.harness.state.runtime.CancellationReader;
import com.pixflow.harness.state.runtime.DefaultCancellationReader;
import com.pixflow.harness.state.runtime.DefaultProgressReader;
import com.pixflow.harness.state.testsupport.FakeAtomicCounter;
import com.pixflow.harness.state.testsupport.FakeCacheStore;
import com.pixflow.harness.state.testsupport.FakeCheckpointReadPort;
import com.pixflow.harness.state.testsupport.FakeTaskRuntimeKeyPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionStateServiceTest {

    @Test
    void assemblesSnapshotFromStatusProgressAndCancel() {
        FakeCheckpointReadPort checkpoint = new FakeCheckpointReadPort();
        checkpoint.putTask(
                "task-1",
                new SkippableWorkUnits("task-1", Set.of()),
                new PersistedCounts(10, 4, 1),
                TaskRunStatus.RUNNING);
        FakeTaskRuntimeKeyPort keyPort = new FakeTaskRuntimeKeyPort();
        FakeAtomicCounter counter = new FakeAtomicCounter();
        counter.put(keyPort.progressKey("task-1"), 5);
        FakeCacheStore cache = new FakeCacheStore();
        cache.put(keyPort.cancelKey("task-1"), false, keyPort.cancelKey("task-1").suggestedTtl());

        ExecutionStateService service = new DefaultExecutionStateService(
                checkpoint,
                new DefaultProgressReader(checkpoint, keyPort, counter, new StateProperties(), new NoopStateMetrics()),
                new DefaultCancellationReader(cache, keyPort),
                new NoopStateMetrics(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC));

        var snapshot = service.snapshot("task-1");

        assertThat(snapshot.taskId()).isEqualTo("task-1");
        assertThat(snapshot.status()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(snapshot.progress().source()).isEqualTo(ProgressSource.REDIS);
        assertThat(snapshot.progress().done()).isEqualTo(5);
        assertThat(snapshot.cancelRequested()).isFalse();
        assertThat(snapshot.snapshotAt()).isEqualTo("2026-06-28T10:00:00Z");
    }

    @Test
    void missingTaskRaisesNotFound() {
        FakeCheckpointReadPort checkpoint = new FakeCheckpointReadPort();
        ExecutionStateService service = new DefaultExecutionStateService(
                checkpoint,
                taskId -> {
                    throw new AssertionError("progress should not be called");
                },
                new CancellationReader() {
                    @Override
                    public boolean isCancelRequested(String taskId) {
                        return false;
                    }

                    @Override
                    public void throwIfCancelled(String taskId) {
                    }
                },
                new NoopStateMetrics(),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.snapshot("missing"))
                .isInstanceOf(PixFlowException.class)
                .extracting(ex -> ((PixFlowException) ex).code())
                .isEqualTo(StateErrorCode.STATE_TASK_NOT_FOUND);
    }
}
