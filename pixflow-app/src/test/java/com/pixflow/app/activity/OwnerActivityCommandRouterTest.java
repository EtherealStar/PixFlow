package com.pixflow.app.activity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.module.file.api.activity.FileActivityCommandService;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.task.api.TaskCommandService;
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
                .cancel(upload, new AuthPrincipal(7L, "admin", "Administrator"));

        verify(files).cancel(FileActivitySourceKind.UPLOAD, "upload-7");
    }
}
