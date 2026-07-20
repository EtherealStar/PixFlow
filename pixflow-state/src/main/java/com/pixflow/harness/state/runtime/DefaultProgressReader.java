package com.pixflow.harness.state.runtime;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.config.StateProperties;
import com.pixflow.harness.state.error.StateErrorCode;
import com.pixflow.harness.state.model.ProgressView;
import com.pixflow.harness.state.observability.StateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort;
import com.pixflow.harness.state.port.TaskRuntimeKeyPort;
import com.pixflow.infra.cache.counter.AtomicCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultProgressReader implements ProgressReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultProgressReader.class);

    private final CheckpointReadPort checkpointReadPort;

    private final TaskRuntimeKeyPort keyPort;

    private final AtomicCounter counter;

    private final StateProperties properties;

    private final StateMetrics metrics;

    public DefaultProgressReader(
            CheckpointReadPort checkpointReadPort,
            TaskRuntimeKeyPort keyPort,
            AtomicCounter counter,
            StateProperties properties,
            StateMetrics metrics) {
        this.checkpointReadPort = checkpointReadPort;
        this.keyPort = keyPort;
        this.counter = counter;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public ProgressView read(String taskId) {
        var persisted = checkpointReadPort.loadCounts(taskId)
                .orElseThrow(() -> new PixFlowException(
                        StateErrorCode.STATE_TASK_NOT_FOUND, "Task not found: " + taskId))
                .toProgress();
        if (!properties.getProgress().isPreferRedis()) {
            ProgressView view = ProgressView.fromMysql(persisted);
            metrics.recordProgressSource(view.source());
            return view;
        }

        try {
            long redisDone = counter.get(keyPort.progressKey(taskId));
            ProgressView view = ProgressView.fromRedis(persisted, redisDone);
            metrics.recordProgressSource(view.source());
            metrics.recordProgressDrift(view.drift());
            if (Math.abs(view.drift()) > properties.getProgress().getDriftWarnThreshold()) {
                LOGGER.warn("state progress drift exceeds threshold: taskId={}, drift={}", taskId, view.drift());
            }
            return view;
        } catch (RuntimeException ex) {
            // 进度展示有 MySQL 权威计数可回退；不能让 Redis 抖动拖挂状态查询。
            LOGGER.warn("failed to read redis progress, fallback to mysql counts: taskId={}", taskId, ex);
            ProgressView view = ProgressView.fromMysql(persisted);
            metrics.recordProgressSource(view.source());
            return view;
        }
    }
}
