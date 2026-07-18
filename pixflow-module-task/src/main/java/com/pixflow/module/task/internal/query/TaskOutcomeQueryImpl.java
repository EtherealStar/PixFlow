package com.pixflow.module.task.internal.query;

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
    return Optional.ofNullable(mapper.selectById(resultId))
        .filter(this::visibleSuccess)
        .map(this::snapshot);
  }

  @Override
  public List<SuccessfulResultSnapshot> successfulResults(long taskId) {
    return mapper.findVisibleByTaskIdAndStatus(taskId, ResultStatus.SUCCESS).stream()
        .filter(this::visibleSuccess)
        .map(this::snapshot)
        .toList();
  }

  private boolean visibleSuccess(ProcessResult result) {
    return result.getStatus() == ResultStatus.SUCCESS
        && result.getDeletedAt() == null
        && result.getPublishedReferenceKey() != null
        && result.getGeneratedImageId() != null;
  }

  private SuccessfulResultSnapshot snapshot(ProcessResult result) {
    return new SuccessfulResultSnapshot(
        result.getId(),
        result.getTaskId(),
        result.getUnitKind().name(),
        result.getImageId(),
        result.getSkuId(),
        result.getGroupKey(),
        result.getViewId(),
        result.getBranchId(),
        result.getGeneratedImageId(),
        result.getPublishedReferenceKey(),
        result.getBytesOut() == null ? -1 : result.getBytesOut(),
        result.getFinishedAt());
  }
}
