package com.pixflow.module.task.internal.worker;

public record UnitExecutionContext(String workerRunId, int attemptCount, int totalUnits) {
}
