package com.pixflow.module.task.internal.recovery;

import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.time.Clock;

public class HeartbeatWriter {
    private final ProcessTaskMapper taskMapper;
    private final Clock clock;

    public HeartbeatWriter(ProcessTaskMapper taskMapper, Clock clock) {
        this.taskMapper = taskMapper;
        this.clock = clock;
    }

    public void heartbeat(long taskId) {
        taskMapper.heartbeat(taskId, clock.instant());
    }
}
