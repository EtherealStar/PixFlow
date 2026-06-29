package com.pixflow.module.task.internal.stateadapter;

import com.pixflow.harness.state.model.CompletedUnits;
import com.pixflow.harness.state.model.TaskRunStatus;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.port.CheckpointReadPort;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.model.UnitKind;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class CheckpointReadPortImpl implements CheckpointReadPort {
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;

    public CheckpointReadPortImpl(ProcessTaskMapper taskMapper, ProcessResultMapper resultMapper) {
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
    }

    @Override
    public Optional<CompletedUnits> loadCompletedUnits(String taskId) {
        long id = Long.parseLong(taskId);
        Set<UnitKey> keys = resultMapper.findByTaskIdAndStatus(id, ResultStatus.SUCCESS).stream()
                .filter(result -> result.getKind() != UnitKind.GENERATIVE)
                .map(result -> result.getKind() == UnitKind.GROUP
                        ? UnitKey.group(taskId, result.getGroupKey(), result.getBranchId())
                        : UnitKey.branch(taskId, result.getImageId(), result.getBranchId()))
                .collect(Collectors.toSet());
        return Optional.of(new CompletedUnits(taskId, keys));
    }

    @Override
    public Optional<PersistedCounts> loadCounts(String taskId) {
        ProcessTask task = taskMapper.selectById(Long.parseLong(taskId));
        if (task == null) {
            return Optional.empty();
        }
        long id = task.getId();
        return Optional.of(new PersistedCounts(
                task.getTotalCount() == null ? 0 : task.getTotalCount(),
                resultMapper.countByStatus(id, ResultStatus.SUCCESS),
                resultMapper.countByStatus(id, ResultStatus.FAILED)));
    }

    @Override
    public Optional<TaskRunStatus> loadTaskStatus(String taskId) {
        ProcessTask task = taskMapper.selectById(Long.parseLong(taskId));
        if (task == null) {
            return Optional.empty();
        }
        return Optional.of(toRunStatus(task.getStatus()));
    }

    @Override
    public List<String> listRunningTaskIds(int limit) {
        return taskMapper.findByStatus(TaskStatus.RUNNING, limit).stream()
                .map(ProcessTask::getId)
                .map(String::valueOf)
                .toList();
    }

    private static TaskRunStatus toRunStatus(TaskStatus status) {
        return switch (status) {
            case PENDING, QUEUED -> TaskRunStatus.PENDING;
            case RUNNING -> TaskRunStatus.RUNNING;
            case COMPLETED, PARTIAL -> TaskRunStatus.SUCCEEDED;
            case FAILED -> TaskRunStatus.FAILED;
            case CANCELLED -> TaskRunStatus.CANCELLED;
        };
    }
}
