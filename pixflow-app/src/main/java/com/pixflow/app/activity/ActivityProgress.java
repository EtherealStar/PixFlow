package com.pixflow.app.activity;

public record ActivityProgress(int completed, int total, int failed) {
    public ActivityProgress {
        if (completed < 0 || total < 0 || failed < 0 || completed > total) {
            throw new IllegalArgumentException("invalid activity progress");
        }
    }
}
