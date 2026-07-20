package com.pixflow.app.activity;

public record ActivityFrame(
        long sequence,
        ActivityOperation operation,
        String activityId,
        ActivityView view) {
}
