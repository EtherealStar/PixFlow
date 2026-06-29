package com.pixflow.module.task.internal.progress;

import com.pixflow.module.task.api.event.ProgressEvent;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.progress.ProgressSnapshot;
import com.pixflow.module.task.infra.cache.TaskProgressCounter;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;

public class ProgressAggregator {
    private final TaskProgressCounter counter;
    private final ApplicationEventPublisher publisher;
    private final TaskMetrics metrics;
    private final Clock clock;

    public ProgressAggregator(TaskProgressCounter counter, ApplicationEventPublisher publisher,
                              TaskMetrics metrics, Clock clock) {
        this.counter = counter;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void success(String taskId, int total) {
        counter.incrementDone(taskId);
        publish(taskId, total, TaskStatus.RUNNING);
    }

    public void failed(String taskId, int total) {
        counter.incrementDone(taskId);
        counter.incrementFailed(taskId);
        publish(taskId, total, TaskStatus.RUNNING);
    }

    public void skipped(String taskId, int total) {
        counter.incrementDone(taskId);
        counter.incrementSkipped(taskId);
        publish(taskId, total, TaskStatus.RUNNING);
    }

    public ProgressSnapshot snapshot(String taskId, int total) {
        return counter.snapshot(taskId, total);
    }

    public void clear(String taskId) {
        counter.reset(taskId);
    }

    private void publish(String taskId, int total, TaskStatus status) {
        try {
            ProgressSnapshot s = counter.snapshot(taskId, total);
            publisher.publishEvent(new ProgressEvent(taskId, total, s.done(), s.failed(), s.skipped(),
                    status, clock.instant()));
            metrics.recordProgressPublish("ok");
        } catch (RuntimeException e) {
            metrics.recordProgressPublish("failed");
        }
    }
}
