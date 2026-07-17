package com.pixflow.module.task.api;

import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.api.command.CreateTaskCommand;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.RetryTaskResponse;

public interface TaskCommandService {
    TaskId createAndEnqueue(CreateTaskCommand command);

    boolean cancel(CancelTaskCommand command);

    RetryTaskResponse retryFailed(RetryFailedTaskCommand command);
}
