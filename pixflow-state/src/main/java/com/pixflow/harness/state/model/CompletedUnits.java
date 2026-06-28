package com.pixflow.harness.state.model;

import java.util.Set;

public record CompletedUnits(String taskId, Set<UnitKey> succeeded) {

    public CompletedUnits {
        taskId = requireText(taskId, "taskId");
        succeeded = Set.copyOf(succeeded == null ? Set.of() : succeeded);
        for (UnitKey unit : succeeded) {
            if (!taskId.equals(unit.taskId())) {
                throw new IllegalArgumentException("succeeded unit taskId must match CompletedUnits taskId");
            }
        }
    }

    public boolean isDone(UnitKey unit) {
        return succeeded.contains(unit);
    }

    public int size() {
        return succeeded.size();
    }

    public static CompletedUnits empty(String taskId) {
        return new CompletedUnits(taskId, Set.of());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
