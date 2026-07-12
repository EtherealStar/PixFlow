package com.pixflow.harness.state.model;

import java.util.Set;

public record SkippableWorkUnits(String taskId, Set<UnitKey> succeeded) {
    public SkippableWorkUnits {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        taskId = taskId.trim();
        succeeded = Set.copyOf(succeeded == null ? Set.of() : succeeded);
        for (UnitKey unit : succeeded) {
            if (!taskId.equals(unit.taskId())) {
                throw new IllegalArgumentException("succeeded unit taskId must match taskId");
            }
        }
    }

    public boolean contains(UnitKey unit) {
        return succeeded.contains(unit);
    }

    public int size() {
        return succeeded.size();
    }

    public static SkippableWorkUnits empty(String taskId) {
        return new SkippableWorkUnits(taskId, Set.of());
    }
}
