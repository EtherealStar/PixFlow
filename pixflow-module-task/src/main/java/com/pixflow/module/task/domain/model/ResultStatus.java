package com.pixflow.module.task.domain.model;

public enum ResultStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED;
    }
}
