package com.pixflow.harness.state.port;

import com.pixflow.harness.state.model.SkippableWorkUnits;
import com.pixflow.harness.state.model.ProgressView.PersistedProgress;
import com.pixflow.harness.state.model.TaskRunStatus;
import java.util.List;
import java.util.Optional;

public interface CheckpointReadPort {

    Optional<SkippableWorkUnits> loadSkippableWorkUnits(String taskId);

    Optional<PersistedCounts> loadCounts(String taskId);

    Optional<TaskRunStatus> loadTaskStatus(String taskId);

    List<String> listRunningTaskIds(int limit);

    record PersistedCounts(int total, int succeeded, int failed) {
        public PersistedCounts {
            if (total < 0 || succeeded < 0 || failed < 0) {
                throw new IllegalArgumentException("persisted counts must not be negative");
            }
        }

        public PersistedProgress toProgress() {
            return new PersistedProgress(total, succeeded, failed);
        }
    }
}
