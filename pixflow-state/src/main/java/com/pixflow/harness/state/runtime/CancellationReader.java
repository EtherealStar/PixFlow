package com.pixflow.harness.state.runtime;

public interface CancellationReader {
    boolean isCancelRequested(String taskId);

    void throwIfCancelled(String taskId);
}
