package com.pixflow.app.activity;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.file.api.activity.FileActivityCommandService;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.api.command.ClearTaskCommand;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.RetryTaskResponse;
import com.pixflow.module.task.api.command.TaskId;
import java.util.Objects;

public final class OwnerActivityCommandRouter implements ActivityCommandRouter {
    private final TaskCommandService tasks;

    private final FileActivityCommandService files;

    public OwnerActivityCommandRouter(TaskCommandService tasks, FileActivityCommandService files) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.files = Objects.requireNonNull(files, "files");
    }

    @Override
    public ActivityRetryResult retryFailed(ActivityCommandTarget target, AuthPrincipal principal) {
        ActivityView activity = target.view();
        if (!activity.allowedActions().retryFailed() || activity.taskId() == null
                || target.sourceKind() != ActivitySourceKind.TASK) {
            throw rejected("activity cannot retry failed work units");
        }
        RetryTaskResponse retried = tasks.retryFailed(
                new RetryFailedTaskCommand(new TaskId(activity.taskId())));
        // activityId 是 App 的 opaque identity；前端不需要知道其 task: 前缀规则。
        return new ActivityRetryResult(
                activity.activityId(),
                "task:" + retried.taskId(),
                retried.taskId(),
                retried.retryOfTaskId());
    }

    @Override
    public void cancel(ActivityCommandTarget target, AuthPrincipal principal) {
        ActivityView activity = target.view();
        if (!activity.allowedActions().cancel()) {
            throw rejected("activity cannot be cancelled");
        }
        if (activity.taskId() != null) {
            boolean cancelled = tasks.cancel(new CancelTaskCommand(
                    Long.parseLong(activity.taskId()), activity.conversationId(), principal.userId(),
                    "user_cancelled"));
            if (!cancelled) {
                throw rejected("task no longer accepts cancellation");
            }
            return;
        }
        files.cancel(fileKind(target.sourceKind()), target.sourceId());
    }

    @Override
    public void clear(ActivityCommandTarget target, AuthPrincipal principal) {
        ActivityView activity = target.view();
        if (!activity.allowedActions().clear()) {
            throw rejected("activity cannot be cleared");
        }
        if (activity.taskId() != null) {
            boolean cleared = tasks.clear(new ClearTaskCommand(
                    Long.parseLong(activity.taskId()), activity.conversationId(), principal.userId()));
            if (!cleared) {
                throw rejected("task no longer accepts cleanup");
            }
            return;
        }
        files.clear(fileKind(target.sourceKind()), target.sourceId());
    }

    private static FileActivitySourceKind fileKind(ActivitySourceKind sourceKind) {
        return switch (sourceKind) {
            case UPLOAD -> FileActivitySourceKind.UPLOAD;
            case PACKAGE -> FileActivitySourceKind.PACKAGE;
            case TASK -> throw rejected("task activity is missing task identity");
        };
    }

    private static PixFlowException rejected(String message) {
        return new PixFlowException(CommonErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

}
