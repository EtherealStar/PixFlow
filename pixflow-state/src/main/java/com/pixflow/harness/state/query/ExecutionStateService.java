package com.pixflow.harness.state.query;

import com.pixflow.harness.state.model.ExecutionStateSnapshot;

public interface ExecutionStateService {
    ExecutionStateSnapshot snapshot(String taskId);
}
