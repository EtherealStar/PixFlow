package com.pixflow.module.task.internal.scheduler;

import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.domain.model.WorkUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WorkUnitScheduler {
    private final Executor processExecutor;
    private final Executor imagegenExecutor;

    public WorkUnitScheduler(TaskProperties properties) {
        this.processExecutor = executor("pixflow-task-process-", properties.getWorker().getProcessPool());
        this.imagegenExecutor = executor("pixflow-task-imagegen-", properties.getWorker().getImagegenPool());
    }

    public List<CompletableFuture<Void>> submitAll(List<WorkUnit> units, Consumer<WorkUnit> runner) {
        return units.stream()
                .map(unit -> CompletableFuture.runAsync(() -> runner.accept(unit), executor(unit.taskType())))
                .toList();
    }

    private Executor executor(TaskType type) {
        return type == TaskType.IMAGE_GEN ? imagegenExecutor : processExecutor;
    }

    private static Executor executor(String name, TaskProperties.Pool pool) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                pool.getCoreSize(), pool.getMaxSize(), 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(pool.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName(name + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
