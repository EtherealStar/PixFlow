package com.pixflow.harness.eval.model;

public enum TurnStatus {
    OPEN(0),
    COMMITTED(1),
    ABORTED(2),
    CANCELLED(3);

    private final int code;

    TurnStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean canReplace(TurnStatus existing) {
        if (existing == null) {
            return true;
        }
        return existing == OPEN || this != OPEN;
    }
}
