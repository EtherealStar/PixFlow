package com.pixflow.module.task.internal.query;

import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.List;
import java.util.Optional;

public final class TaskOutcomeQueryImpl implements TaskOutcomeQuery {
  private final ProcessResultMapper mapper;

  private final ProcessTaskMapper taskMapper;

  public TaskOutcomeQueryImpl(ProcessResultMapper mapper, ProcessTaskMapper taskMapper) {
    this.mapper = mapper;
    this.taskMapper = taskMapper;
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

  @Override
  public Optional<CopyResultSnapshot> successfulCopy(long resultId) {
    return Optional.ofNullable(mapper.selectById(resultId))
        .filter(result -> result.getStatus() == ResultStatus.SUCCESS)
        .filter(result -> result.getDeletedAt() == null)
        .filter(result -> result.getGeneratedCopy() != null)
        .filter(result -> !result.getGeneratedCopy().isBlank())
        .map(result -> new CopyResultSnapshot(
            result.getId(),
            result.getTaskId(),
            result.getGeneratedCopy(),
            result.getProducerProvider(),
            result.getProducerModel(),
            result.getFinishedAt()));
  }

  @Override
  public Optional<ConfirmedDecisionSnapshot> confirmedDecision(long taskId, String revision) {
    if (revision == null || revision.isBlank()) {
      throw new IllegalArgumentException("decision revision must not be blank");
    }
    return Optional.ofNullable(taskMapper.selectById(taskId))
        .filter(task -> revision.equals(task.getPayloadHash()))
        .map(task -> new ConfirmedDecisionSnapshot(
            task.getId(),
            task.getTaskType().name(),
            task.getConversationId(),
            task.getPackageId(),
            task.getDagJson(),
            task.getPayloadHash(),
            task.getSchemaVersion(),
            task.getCreatedAt()));
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
