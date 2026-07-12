package com.pixflow.module.task.internal.recovery;

import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.worker.ExecutionRun;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatWriter implements AutoCloseable {
    private final ProcessTaskMapper taskMapper;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pixflow-task-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public HeartbeatWriter(ProcessTaskMapper taskMapper, Clock clock) {
        this.taskMapper = taskMapper;
        this.clock = clock;
    }

    public HeartbeatSession start(ExecutionRun run, Duration interval) {
        var future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (taskMapper.heartbeatEpoch(Long.parseLong(run.taskId()), run.epoch(), clock.instant()) != 1) {
                    run.deactivate();
                }
            } catch (RuntimeException failure) {
                // 无法证明 epoch 仍有效时 fail-closed，owner 不再提交 completion。
                run.deactivate();
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    public interface HeartbeatSession extends AutoCloseable { @Override void close(); }
}
