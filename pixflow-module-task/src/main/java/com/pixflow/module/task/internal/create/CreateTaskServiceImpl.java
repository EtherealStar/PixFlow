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
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;

public class CreateTaskServiceImpl implements TaskCommandService {
    private final ProcessTaskMapper taskMapper;
    private final TaskMessagePublisher messagePublisher;
    private final IdempotencyGuard idempotencyGuard;
    private final CancellationService cancellationService;
    private final TaskEventPublisher eventPublisher;
    private final TaskMetrics metrics;
    private final Clock clock;

    public CreateTaskServiceImpl(ProcessTaskMapper taskMapper,
                                 TaskMessagePublisher messagePublisher,
                                 IdempotencyGuard idempotencyGuard,
                                 CancellationService cancellationService,
                                 TaskEventPublisher eventPublisher,
                                 TaskMetrics metrics,
                                 Clock clock) {
        this.taskMapper = taskMapper;
        this.messagePublisher = messagePublisher;
        this.idempotencyGuard = idempotencyGuard;
        this.cancellationService = cancellationService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public TaskId createAndEnqueue(CreateTaskCommand command) {
        Instant started = clock.instant();
        return idempotencyGuard.findExistingTaskId(command.idempotencyKey())
                .map(taskId -> {
                    metrics.recordCreate(command.taskType(), "idempotent_hit",
                            Duration.between(started, clock.instant()));
                    return new TaskId(taskId);
                })
                .orElseGet(() -> createNew(command, started));
    }

    @Override
    public boolean cancel(CancelTaskCommand command) {
        return cancellationService.cancel(command);
    }

    private TaskId createNew(CreateTaskCommand command, Instant started) {
        Instant now = clock.instant();
        ProcessTask task = new ProcessTask();
        task.setTaskType(command.taskType());
        task.setConversationId(command.conversationId());
        task.setPackageId(command.packageId());
        task.setIdempotencyKey(command.idempotencyKey());
        task.setPriority(command.priority());
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(command.expectedCount());
        task.setDoneCount(0);
        task.setDagJson(command.payload());
        task.setPayloadHash(command.payloadHash());
        task.setSchemaVersion("1.0");
        task.setAttemptCount(0);
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
        transit(taskId, TaskStatus.PENDING, TaskStatus.QUEUED);
        try {
            messagePublisher.publish(new TaskMessage(Long.toString(taskId), command.taskType(),
                    command.priority(), "1.0"));
        } catch (RuntimeException e) {
            transit(taskId, TaskStatus.QUEUED, TaskStatus.FAILED);
            metrics.recordCreate(command.taskType(), "failed", Duration.between(started, clock.instant()));
            throw e;
        }
        idempotencyGuard.remember(command.idempotencyKey(), Long.toString(taskId));
        eventPublisher.publishCreated(new TaskCreatedEvent(Long.toString(taskId), command.taskType(),
                command.conversationId(), command.packageId(), clock.instant()));
        metrics.recordCreate(command.taskType(), "ok", Duration.between(started, clock.instant()));
        return TaskId.of(taskId);
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
