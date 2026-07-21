package com.pixflow.app.activity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.file.api.activity.FileActivityCommandService;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.command.RetryFailedTaskCommand;
import com.pixflow.module.task.api.command.RetryTaskResponse;
import com.pixflow.module.task.api.command.TaskId;
import com.pixflow.module.task.domain.model.TaskStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OwnerActivityCommandRouterTest {
    @Test
    void uploadCancellationUsesTheFileOwnerCommandInsteadOfPackageDeletion() {
        TaskCommandService tasks = mock(TaskCommandService.class);
        FileActivityCommandService files = mock(FileActivityCommandService.class);
        ActivityView upload = new ActivityView(
                "upload:upload-7", ActivityKind.UPLOAD, ActivityStatus.UPLOADING,
                new ActivityProgress(2, 4, 0), null, null, null,
                Instant.parse("2026-07-20T08:00:00Z"), null, null,
                new ActivityActions(true, false, false), 3);

        new OwnerActivityCommandRouter(tasks, files)
                .cancel(new ActivityCommandTarget(ActivitySourceKind.UPLOAD, "upload-7", upload),
                        new AuthPrincipal(7L, "admin", "Administrator"));

        verify(files).cancel(FileActivitySourceKind.UPLOAD, "upload-7");
    }

    @Test
    void retryReturnsAnOpaqueActivityProjection() {
        TaskCommandService tasks = mock(TaskCommandService.class);
        FileActivityCommandService files = mock(FileActivityCommandService.class);
        ActivityView failed = new ActivityView(
                "task:42", ActivityKind.PROCESS, ActivityStatus.FAILED,
                new ActivityProgress(3, 5, 2), "conversation-9", null, "42",
                Instant.parse("2026-07-20T08:00:00Z"), null, Instant.parse("2026-07-20T08:05:00Z"),
                new ActivityActions(false, true, true), 8);
        when(tasks.retryFailed(new RetryFailedTaskCommand(new TaskId("42"))))
                .thenReturn(new RetryTaskResponse("84", "42", 2, TaskStatus.PENDING));

        ActivityRetryResult result = new OwnerActivityCommandRouter(tasks, files)
                .retryFailed(new ActivityCommandTarget(ActivitySourceKind.TASK, "42", failed),
                        new AuthPrincipal(7L, "admin", "Administrator"));

        assertThat(result).isEqualTo(new ActivityRetryResult("task:42", "task:84", "84", "42"));
    }
}
