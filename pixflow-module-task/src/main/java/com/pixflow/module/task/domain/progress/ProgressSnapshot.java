package com.pixflow.module.task.domain.progress;

public record ProgressSnapshot(int total, int done, int failed, int skipped) {
  public ProgressSnapshot {
    if (total < 0 || done < 0 || failed < 0 || skipped < 0) {
      throw new IllegalArgumentException("progress counts must not be negative");
    }
  }
}
