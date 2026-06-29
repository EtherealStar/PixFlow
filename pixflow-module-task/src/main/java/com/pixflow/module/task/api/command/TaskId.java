package com.pixflow.module.task.api.command;

public record TaskId(String value) {
    public TaskId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        value = value.trim();
    }

    public static TaskId of(long value) {
        return new TaskId(Long.toString(value));
    }
}
