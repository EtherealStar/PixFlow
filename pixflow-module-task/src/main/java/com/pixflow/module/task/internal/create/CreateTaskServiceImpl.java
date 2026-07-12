package com.pixflow.module.task.internal.create;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.api.command.CreateTaskCommand;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.event.TaskCreatedEvent;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.idempotency.IdempotencyGuard;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskStatus;
import com.pixflow.module.task.domain.statemachine.TaskStateMachine;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.mq.TaskMessage;
import com.pixflow.module.task.infra.mq.TaskMessagePublisher;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.internal.planning.WorkUnitPlanner;
import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class CreateTaskServiceImpl implements TaskCommandService {
    private final ProcessTaskMapper taskMapper;
    private final TaskMessagePublisher messagePublisher;
    private final IdempotencyGuard idempotencyGuard;
    private final CancellationService cancellationService;
    private final TaskEventPublisher eventPublisher;
    private final TaskMetrics metrics;
    private final Clock clock;
    private final ProcessResultMapper resultMapper;
    private final WorkUnitPlanner planner;
    private final ObjectMapper objectMapper;

    public CreateTaskServiceImpl(ProcessTaskMapper taskMapper,
                                 TaskMessagePublisher messagePublisher,
                                 IdempotencyGuard idempotencyGuard,
                                 CancellationService cancellationService,
                                 TaskEventPublisher eventPublisher,
                                 TaskMetrics metrics,
                                 Clock clock,
                                 ProcessResultMapper resultMapper,
                                 WorkUnitPlanner planner,
                                 ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.messagePublisher = messagePublisher;
        this.idempotencyGuard = idempotencyGuard;
        this.cancellationService = cancellationService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
        this.resultMapper = resultMapper;
        this.planner = planner;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TaskId createAndEnqueue(CreateTaskCommand command) {
        Instant started = clock.instant();
        return idempotencyGuard.findExistingTaskId(command.idempotencyKey())
                .map(existingTaskId -> {
                    metrics.recordCreate(command.taskType(), "idempotent_hit",
                            Duration.between(started, clock.instant()));
                    return new TaskId(existingTaskId);
                })
                .orElseGet(() -> createNew(command, started));
    }

    @Override
    public boolean cancel(CancelTaskCommand command) {
        return cancellationService.cancel(command);
    }

    private TaskId createNew(CreateTaskCommand command, Instant started) {
        Instant now = clock.instant();
        var selection = command.taskType() == com.pixflow.module.task.domain.model.TaskType.IMAGE_PROCESS
                ? planner.plan(command.packageId(), command.payload())
                : planner.planGenerative(command.payload());
        ProcessTask task = new ProcessTask();
        task.setTaskType(command.taskType());
        task.setConversationId(command.conversationId());
        task.setPackageId(command.packageId());
        task.setIdempotencyKey(command.idempotencyKey());
        task.setPriority(command.priority());
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(selection.items().isEmpty() ? command.expectedCount() : selection.items().size());
        task.setDoneCount(0);
        task.setDagJson(command.payload());
        task.setPayloadHash(command.payloadHash());
        task.setSchemaVersion("1.0");
        task.setRunEpoch(0L);
        try {
            task.setUnitSelectionJson(objectMapper.writeValueAsString(selection));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new PixFlowException(TaskErrorCode.TASK_CREATE_FAILED, "serialize work unit selection failed", e);
        }
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            return idempotencyGuard.findExistingTaskId(command.idempotencyKey())
                    .map(TaskId::new)
                    .orElseThrow(() -> duplicate);
        } catch (RuntimeException e) {
            metrics.recordCreate(command.taskType(), "failed", Duration.between(started, clock.instant()));
            throw new PixFlowException(TaskErrorCode.TASK_CREATE_FAILED, "create task failed", e);
        }

        long taskId = task.getId();
        precreatePendingResults(taskId, selection, now);
        transit(taskId, TaskStatus.PENDING, TaskStatus.QUEUED);
        Runnable publish = () -> publishCreatedTask(taskId, command, started);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // task、冻结 selection 与 PENDING 投影先原子提交；MQ 和事件只能观察已提交事实。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
        return TaskId.of(taskId);
    }

    private void publishCreatedTask(long taskId, CreateTaskCommand command, Instant started) {
        try {
            messagePublisher.publish(new TaskMessage(Long.toString(taskId), command.taskType(),
                    command.priority(), "1.0"));
        } catch (RuntimeException e) {
            metrics.recordCreate(command.taskType(), "failed", Duration.between(started, clock.instant()));
            throw e;
        }
        idempotencyGuard.remember(command.idempotencyKey(), Long.toString(taskId));
        eventPublisher.publishCreated(new TaskCreatedEvent(Long.toString(taskId), command.taskType(),
                command.conversationId(), command.packageId(), clock.instant()));
        metrics.recordCreate(command.taskType(), "ok", Duration.between(started, clock.instant()));
    }

    private void precreatePendingResults(long taskId,
            com.pixflow.module.task.internal.planning.WorkUnitSelection selection, Instant now) {
        for (var item : selection.items()) {
            ProcessResult result = new ProcessResult();
            result.setTaskId(taskId);
            result.setUnitKind(item.kind());
            result.setBranchId(item.branchId());
            result.setRunEpoch(0L);
            result.setStatus(ResultStatus.PENDING);
            result.setCreatedAt(now);
            UnitKey key = new UnitKey(Long.toString(taskId), item.kind(), item.memberId(), item.branchId());
            result.setUnitKey(UnitKeyCodec.encode(key));
            if (!item.images().isEmpty()) {
                var image = item.images().get(0);
                result.setImageId(image.imageId());
                result.setSkuId(image.skuId());
                result.setGroupKey(image.groupKey());
                result.setViewId(image.viewId());
                result.setSourcePath(image.objectKey());
            }
            // PENDING 行是冻结 selection 的数据库投影，执行线程只更新它，不能另插一套身份。
            resultMapper.insert(result);
        }
    }

    private void transit(long taskId, TaskStatus from, TaskStatus to) {
        TaskStateMachine.INSTANCE.verify(from, to);
        int updated = taskMapper.transit(taskId, from, to, clock.instant());
        if (updated != 1) {
            throw new PixFlowException(TaskErrorCode.TASK_STATE_TRANSITION_REJECTED,
                    "task state changed before transition");
        }
    }
}
