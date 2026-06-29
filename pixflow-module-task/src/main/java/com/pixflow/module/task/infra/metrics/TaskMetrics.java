package com.pixflow.module.task.infra.metrics;

import com.pixflow.common.error.ErrorCode;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.TaskType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

public class TaskMetrics {
    private final MeterRegistry registry;

    public TaskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCreate(TaskType type, String result, Duration duration) {
        Counter.builder("pixflow.task.create")
                .tag("task_type", tag(type))
                .tag("result", result)
                .register(registry)
                .increment();
        Timer.builder("pixflow.task.create.duration")
                .tag("task_type", tag(type))
                .register(registry)
                .record(duration);
    }

    public void recordWorker(TaskType type, String outcome, Duration duration) {
        Counter.builder("pixflow.task.worker.exec")
                .tag("task_type", tag(type))
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("pixflow.task.worker.exec.duration")
                .tag("task_type", tag(type))
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void recordTerminal(TaskStatus status) {
        Counter.builder("pixflow.task.terminal")
                .tag("state", status.name().toLowerCase())
                .register(registry)
                .increment();
    }

    public void recordFailure(ErrorCode code) {
        Counter.builder("pixflow.task.worker.failure")
                .tag("code", code.code())
                .register(registry)
                .increment();
    }

    public void recordProgressPublish(String result) {
        Counter.builder("pixflow.task.progress.publish")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void recordCancel(String state) {
        Counter.builder("pixflow.task.cancel")
                .tag("state", state)
                .register(registry)
                .increment();
    }

    public void recordDownload(String type, String result) {
        Counter.builder("pixflow.task.download")
                .tag("type", type)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void recordLockContention() {
        Counter.builder("pixflow.task.lock.contention")
                .tag("lock", "task_execution")
                .register(registry)
                .increment();
    }

    public void recordRejectedTransition(String from, String to) {
        Counter.builder("pixflow.task.state.transition.rejected")
                .tag("from", from)
                .tag("to", to)
                .register(registry)
                .increment();
    }

    private static String tag(TaskType type) {
        return type.name().toLowerCase();
    }
}
