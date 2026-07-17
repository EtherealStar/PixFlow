package com.pixflow.module.task.internal.scheduler;

import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.domain.model.WorkUnit;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.internal.worker.WorkUnitCompletion;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class WorkUnitScheduler implements AutoCloseable {
    private final ThreadPoolExecutor processExecutor;
    private final ThreadPoolExecutor imagegenExecutor;

    public WorkUnitScheduler(TaskProperties properties, TaskMetrics metrics) {
        this.processExecutor = executor("process", "pixflow-task-process-",
                properties.getWorker().getProcessPool(), metrics);
        this.imagegenExecutor = executor("imagegen", "pixflow-task-imagegen-",
                properties.getWorker().getImagegenPool(), metrics);
        metrics.bindPool("process", processExecutor);
        metrics.bindPool("imagegen", imagegenExecutor);
    }

    public List<CompletableFuture<WorkUnitCompletion>> submitAll(
            List<WorkUnit> units, Function<WorkUnit, WorkUnitCompletion> runner) {
        return units.stream()
                .map(unit -> CompletableFuture.supplyAsync(() -> runner.apply(unit), executor(unit.taskType())))
                .toList();
    }

    private Executor executor(TaskType type) {
        return type == TaskType.IMAGE_GEN ? imagegenExecutor : processExecutor;
    }

    private static ThreadPoolExecutor executor(String poolName, String name, TaskProperties.Pool pool,
                                               TaskMetrics metrics) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                pool.getCoreSize(), pool.getMaxSize(), 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(pool.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName(name + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                });
        var callerRuns = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler((task, rejectedExecutor) -> {
            metrics.recordPoolRejected(poolName);
            callerRuns.rejectedExecution(task, rejectedExecutor);
        });
        return executor;
    }

    @Override
    public void close() {
        processExecutor.shutdown();
        imagegenExecutor.shutdown();
    }
}
