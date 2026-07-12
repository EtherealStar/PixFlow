package com.pixflow.harness.state.recovery;

import com.pixflow.harness.state.model.SkippableWorkUnits;

public interface RecoveryCoordinator {
    SkippableWorkUnits resolveSkippable(String taskId);
}
