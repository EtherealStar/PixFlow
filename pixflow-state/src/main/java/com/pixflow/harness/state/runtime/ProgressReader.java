package com.pixflow.harness.state.runtime;

import com.pixflow.harness.state.model.ProgressView;

public interface ProgressReader {
    ProgressView read(String taskId);
}
