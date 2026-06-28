package com.pixflow.harness.state.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.model.CompletedUnits;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.observability.NoopStateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort.PersistedCounts;
import com.pixflow.harness.state.model.TaskRunStatus;
import com.pixflow.harness.state.testsupport.FakeCheckpointReadPort;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryCoordinatorTest {

    @Test
    void returnsOnlyMysqlSucceededUnitsAsSkippable() {
        FakeCheckpointReadPort checkpoint = new FakeCheckpointReadPort();
        UnitKey branch = UnitKey.branch("task-1", "image-1", "branch-a");
        UnitKey group = UnitKey.group("task-1", "group-1", "branch-b");
        checkpoint.putTask(
                "task-1",
                new CompletedUnits("task-1", Set.of(branch, group)),
                new PersistedCounts(3, 2, 1),
                TaskRunStatus.RUNNING);

        CompletedUnits skippable = new DefaultRecoveryCoordinator(checkpoint, new NoopStateMetrics())
                .resolveSkippable("task-1");

        assertThat(skippable.succeeded()).containsExactlyInAnyOrder(branch, group);
        assertThat(skippable.isDone(UnitKey.branch("task-1", "image-2", "branch-a"))).isFalse();
    }

    @Test
    void unknownTaskRaisesStateTaskNotFound() {
        RecoveryCoordinator coordinator = new DefaultRecoveryCoordinator(
                new FakeCheckpointReadPort(), new NoopStateMetrics());

        assertThatThrownBy(() -> coordinator.resolveSkippable("missing"))
                .isInstanceOf(PixFlowException.class)
                .extracting(ex -> ((PixFlowException) ex).code())
                .isEqualTo(StateErrorCode.STATE_TASK_NOT_FOUND);
    }
}
