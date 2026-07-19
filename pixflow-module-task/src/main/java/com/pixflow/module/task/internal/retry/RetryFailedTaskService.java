package com.pixflow.module.task.internal.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.RetryTaskResponse;
import com.pixflow.module.task.api.event.TaskCreatedEvent;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.idempotency.IdempotencyGuard;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.internal.create.PendingTaskEnqueuer;
import com.pixflow.module.task.internal.planning.WorkUnitSelection;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class RetryFailedTaskService {
  private final ProcessTaskMapper taskMapper;

  private final ProcessResultMapper resultMapper;

  private final IdempotencyGuard idempotencyGuard;

  private final PendingTaskEnqueuer enqueuer;

  private final ObjectMapper objectMapper;

  private final Clock clock;

  private final TaskEventPublisher eventPublisher;

  public RetryFailedTaskService(
      ProcessTaskMapper taskMapper,
      ProcessResultMapper resultMapper,
      IdempotencyGuard idempotencyGuard,
      PendingTaskEnqueuer enqueuer,
      ObjectMapper objectMapper,
      Clock clock,
      TaskEventPublisher eventPublisher) {
    this.taskMapper = taskMapper;
    this.resultMapper = resultMapper;
    this.idempotencyGuard = idempotencyGuard;
    this.enqueuer = enqueuer;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public RetryTaskResponse retry(RetryFailedTaskCommand command) {
    long sourceId = parseSourceId(command);
    String idempotencyKey = idempotencyKey(sourceId);
    return idempotencyGuard
        .findExistingTaskId(idempotencyKey)
        .map(this::loadResponse)
        .orElseGet(() -> create(sourceId, idempotencyKey));
  }

  private RetryTaskResponse create(long sourceId, String idempotencyKey) {
    // 来源行锁保证 FAILED 集合与终态快照属于同一个不可变事务视图。
    ProcessTask source = taskMapper.lockById(sourceId);
    if (source == null) {
      throw new PixFlowException(TaskErrorCode.TASK_NOT_FOUND, "task not found: " + sourceId);
    }
    if (source.getStatus() != TaskStatus.PARTIAL && source.getStatus() != TaskStatus.FAILED) {
      throw new PixFlowException(
          TaskErrorCode.TASK_RETRY_SOURCE_NOT_TERMINAL, "retry source must be PARTIAL or FAILED");
    }

    var failedKeys = new HashSet<String>();
    for (ProcessResult result : resultMapper.findByTaskIdAndStatus(sourceId, ResultStatus.FAILED)) {
      failedKeys.add(result.getUnitKey());
    }
    WorkUnitSelection sourceSelection = readSelection(source);
    var failedItems =
        sourceSelection.items().stream()
            .filter(
                item ->
                    failedKeys.contains(
                        UnitKeyCodec.encode(
                            new UnitKey(
                                Long.toString(sourceId),
                                item.kind(),
                                item.memberId(),
                                item.branchId()))))
            .toList();
    if (failedItems.isEmpty()) {
      throw new PixFlowException(
          TaskErrorCode.TASK_NO_FAILED_UNITS, "retry source has no failed work units");
    }

    WorkUnitSelection selection = new WorkUnitSelection(failedItems);
    Instant now = clock.instant();
    ProcessTask derived = derivedTask(source, idempotencyKey, selection, now);
    try {
      taskMapper.insert(derived);
    } catch (DuplicateKeyException duplicate) {
      return idempotencyGuard
          .findExistingTaskId(idempotencyKey)
          .map(this::loadResponse)
          .orElseThrow(() -> duplicate);
    }
    precreatePendingResults(derived.getId(), selection, now);
    registerAfterCommit(
        () -> {
          enqueuer.enqueue(derived.getId());
          idempotencyGuard.remember(idempotencyKey, derived.getId().toString());
          eventPublisher.publishCreated(
              new TaskCreatedEvent(
                  derived.getId().toString(),
                  derived.getTaskType(),
                  derived.getConversationId(),
                  derived.getPackageId(),
                  clock.instant()));
        });
    return new RetryTaskResponse(
        derived.getId().toString(), Long.toString(sourceId), failedItems.size(), TaskStatus.QUEUED);
  }

  private static void registerAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
      return;
    }
    action.run();
  }

  private ProcessTask derivedTask(
      ProcessTask source, String idempotencyKey, WorkUnitSelection selection, Instant now) {
    ProcessTask derived = new ProcessTask();
    derived.setTaskType(source.getTaskType());
    derived.setConversationId(source.getConversationId());
    derived.setPackageId(source.getPackageId());
    derived.setIdempotencyKey(idempotencyKey);
    derived.setPriority(source.getPriority());
    derived.setStatus(TaskStatus.PENDING);
    derived.setTotalCount(selection.items().size());
    derived.setDoneCount(0);
    derived.setDagJson(source.getDagJson());
    derived.setPayloadHash(source.getPayloadHash());
    derived.setSchemaVersion(source.getSchemaVersion());
    derived.setRetryOfTaskId(source.getId());
    derived.setRunEpoch(0L);
    derived.setCreatedAt(now);
    derived.setUpdatedAt(now);
    try {
      derived.setUnitSelectionJson(objectMapper.writeValueAsString(selection));
    } catch (JsonProcessingException e) {
      throw new PixFlowException(
          TaskErrorCode.TASK_CREATE_FAILED, "serialize retry selection failed", e);
    }
    return derived;
  }

  private WorkUnitSelection readSelection(ProcessTask source) {
    try {
      return objectMapper.readValue(source.getUnitSelectionJson(), WorkUnitSelection.class);
    } catch (JsonProcessingException e) {
      throw new PixFlowException(
          TaskErrorCode.TASK_CREATE_FAILED, "deserialize source selection failed", e);
    }
  }

  private void precreatePendingResults(long taskId, WorkUnitSelection selection, Instant now) {
    for (var item : selection.items()) {
      ProcessResult result = new ProcessResult();
      result.setTaskId(taskId);
      result.setUnitKind(item.kind());
      result.setBranchId(item.branchId());
      result.setRunEpoch(0L);
      result.setStatus(ResultStatus.PENDING);
      result.setCreatedAt(now);
      result.setUnitKey(
          UnitKeyCodec.encode(
              new UnitKey(Long.toString(taskId), item.kind(), item.memberId(), item.branchId())));
      if (!item.images().isEmpty()) {
        var image = item.images().getFirst();
        result.setImageId(image.imageId());
        result.setSkuId(image.skuId());
        result.setGroupKey(image.groupKey());
        result.setViewId(image.viewId());
        result.setSourcePath(image.location().key());
      }
      // PENDING 行是派生 selection 的投影，来源结果不会被复用或改写。
      resultMapper.insert(result);
    }
  }

  private RetryTaskResponse loadResponse(String taskId) {
    ProcessTask task = taskMapper.selectById(Long.parseLong(taskId));
    if (task == null || task.getRetryOfTaskId() == null) {
      throw new PixFlowException(TaskErrorCode.TASK_NOT_FOUND, "derived task not found: " + taskId);
    }
    return new RetryTaskResponse(
        taskId,
        task.getRetryOfTaskId().toString(),
        task.getTotalCount() == null ? 0 : task.getTotalCount(),
        task.getStatus());
  }

  private static long parseSourceId(RetryFailedTaskCommand command) {
    try {
      return Long.parseLong(command.sourceTaskId().value());
    } catch (NumberFormatException e) {
      throw new PixFlowException(
          TaskErrorCode.TASK_NOT_FOUND, "task not found: " + command.sourceTaskId().value());
    }
  }

  private static String idempotencyKey(long sourceId) {
    // 业务幂等身份只由不可变的来源任务决定，客户端重试不能创建第二个直接派生任务。
    return "retry-failed:" + sourceId;
  }
}
