package com.pixflow.app.activity;

import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.file.api.activity.FileActivityCommandService;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.CancelTaskCommand;
import com.pixflow.module.task.api.command.ClearTaskCommand;
import java.util.Objects;

public final class OwnerActivityCommandRouter implements ActivityCommandRouter {
    private final TaskCommandService tasks;

    private final FileActivityCommandService files;

    public OwnerActivityCommandRouter(TaskCommandService tasks, FileActivityCommandService files) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.files = Objects.requireNonNull(files, "files");
    }

    @Override
    public void cancel(ActivityView activity, AuthPrincipal principal) {
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
        ActivitySource source = fileSource(activity);
        files.cancel(source.kind(), source.id());
    }

    @Override
    public void clear(ActivityView activity, AuthPrincipal principal) {
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
        ActivitySource source = fileSource(activity);
        files.clear(source.kind(), source.id());
    }

    private static ActivitySource fileSource(ActivityView activity) {
        int separator = activity.activityId().indexOf(':');
        if (separator < 1 || separator == activity.activityId().length() - 1) {
            throw rejected("invalid file activity identity");
        }
        FileActivitySourceKind kind = switch (activity.activityId().substring(0, separator)) {
            case "upload" -> FileActivitySourceKind.UPLOAD;
            case "package" -> FileActivitySourceKind.PACKAGE;
            default -> throw rejected("unknown file activity identity");
        };
        return new ActivitySource(kind, activity.activityId().substring(separator + 1));
    }

    private static PixFlowException rejected(String message) {
        return new PixFlowException(CommonErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    private record ActivitySource(FileActivitySourceKind kind, String id) {
    }
}
