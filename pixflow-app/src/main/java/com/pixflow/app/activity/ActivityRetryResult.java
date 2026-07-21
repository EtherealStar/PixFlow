package com.pixflow.app.activity;

import java.util.Objects;

/** Activity 重试的安全 App 投影，不向 wire 层泄露 Task owner 的内部执行统计。 */
public record ActivityRetryResult(
        String sourceActivityId,
        String activityId,
        String taskId,
        String retryOfTaskId) {
    public ActivityRetryResult {
        Objects.requireNonNull(sourceActivityId, "sourceActivityId");
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(retryOfTaskId, "retryOfTaskId");
    }
}
