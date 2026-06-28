package com.pixflow.harness.state.testsupport;

import com.pixflow.harness.state.model.CompletedUnits;
import com.pixflow.harness.state.model.TaskRunStatus;
import com.pixflow.harness.state.port.CheckpointReadPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FakeCheckpointReadPort implements CheckpointReadPort {
    private final Map<String, CompletedUnits> completed = new LinkedHashMap<>();
    private final Map<String, PersistedCounts> counts = new LinkedHashMap<>();
    private final Map<String, TaskRunStatus> statuses = new LinkedHashMap<>();

    public void putTask(String taskId, CompletedUnits completedUnits, PersistedCounts persistedCounts, TaskRunStatus status) {
        completed.put(taskId, completedUnits);
        counts.put(taskId, persistedCounts);
        statuses.put(taskId, status);
    }

    @Override
    public Optional<CompletedUnits> loadCompletedUnits(String taskId) {
        return Optional.ofNullable(completed.get(taskId));
    }

    @Override
    public Optional<PersistedCounts> loadCounts(String taskId) {
        return Optional.ofNullable(counts.get(taskId));
    }

    @Override
    public Optional<TaskRunStatus> loadTaskStatus(String taskId) {
        return Optional.ofNullable(statuses.get(taskId));
    }

    @Override
    public List<String> listRunningTaskIds(int limit) {
        return statuses.entrySet().stream()
                .filter(entry -> entry.getValue() == TaskRunStatus.RUNNING)
                .map(Map.Entry::getKey)
                .limit(limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
