package com.pixflow.module.task.internal.query;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import java.util.List;
import java.util.Optional;

public final class TaskOutcomeQueryImpl implements TaskOutcomeQuery {
    private final ProcessResultMapper mapper;

    public TaskOutcomeQueryImpl(ProcessResultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SuccessfulResultSnapshot> successfulResult(long resultId) {
        return Optional.ofNullable(mapper.selectById(resultId)).filter(this::visibleSuccess).map(this::snapshot);
    }

    @Override
    public List<SuccessfulResultSnapshot> successfulResults(long taskId) {
        return mapper.findVisibleByTaskIdAndStatus(taskId, ResultStatus.SUCCESS).stream()
                .filter(result -> result.getOutputMinioKey() != null)
                .map(this::snapshot)
                .toList();
    }

    private boolean visibleSuccess(ProcessResult result) {
        return result.getStatus() == ResultStatus.SUCCESS
                && result.getDeletedAt() == null
                && result.getOutputMinioKey() != null;
    }

    private SuccessfulResultSnapshot snapshot(ProcessResult result) {
        BucketType bucket = result.getUnitKind() == com.pixflow.harness.state.model.UnitKind.GENERATIVE
                ? BucketType.GENERATED : BucketType.RESULTS;
        return new SuccessfulResultSnapshot(result.getId(), result.getTaskId(), result.getUnitKind().name(),
                result.getImageId(), result.getSkuId(), result.getGroupKey(), result.getViewId(),
                result.getBranchId(), ObjectLocation.of(bucket, result.getOutputMinioKey()),
                result.getBytesOut() == null ? -1 : result.getBytesOut(), result.getFinishedAt());
    }
}
