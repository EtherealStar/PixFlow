package com.pixflow.harness.state.recovery;

import com.pixflow.harness.state.model.CompletedUnits;

public interface RecoveryCoordinator {
    CompletedUnits resolveSkippable(String taskId);
}
