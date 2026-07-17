package com.pixflow.module.task.api;

import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.api.command.CreateTaskCommand;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.RetryTaskResponse;
import java.util.Optional;

public interface TaskCommandService {
    TaskId createAndEnqueue(CreateTaskCommand command);

    Optional<TaskId> findByIdempotencyKey(String idempotencyKey);

    boolean cancel(CancelTaskCommand command);

    RetryTaskResponse retryFailed(RetryFailedTaskCommand command);
}
