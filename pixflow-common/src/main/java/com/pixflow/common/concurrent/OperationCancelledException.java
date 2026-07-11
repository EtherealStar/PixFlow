package com.pixflow.common.concurrent;

import java.util.Objects;

public final class OperationCancelledException extends RuntimeException {
    private final CancellationReason reason;

    public OperationCancelledException(CancellationReason reason) {
        super("operation cancelled: " + Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public CancellationReason reason() {
        return reason;
    }
}
