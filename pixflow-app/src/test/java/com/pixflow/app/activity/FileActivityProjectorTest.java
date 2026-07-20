package com.pixflow.app.activity;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.module.file.api.activity.FileActivitySnapshot;
import com.pixflow.module.file.api.activity.FileActivityPage;
import com.pixflow.module.file.api.activity.FileActivitySource;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.file.api.activity.FileActivityStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileActivityProjectorTest {
    @Test
    void uploadAndPackageUseDifferentStableActivityIdentities() {
        FileActivitySource source = mock(FileActivitySource.class);
        AdministratorEligibility eligibility = mock(AdministratorEligibility.class);
        ActivityProjectionService projection = mock(ActivityProjectionService.class);
        Instant now = Instant.parse("2026-07-20T08:00:00Z");
        FileActivitySnapshot upload = new FileActivitySnapshot(
                FileActivitySourceKind.UPLOAD, "upload-7", 11, FileActivityStatus.UPLOADING,
                2, 4, null, now, now, true, false);
        when(source.listCurrent(1, 100)).thenReturn(new FileActivityPage(List.of(upload), 1, 1, 100));
        when(eligibility.current()).thenReturn(new AuthPrincipal(7L, "admin", "Administrator"));

        new FileActivityProjector(source, eligibility, projection).reconcile();

        ActivitySourceEvent expected = new ActivitySourceEvent(
                7, ActivitySourceKind.UPLOAD, "upload-7", 11, ActivityOperation.UPSERT,
                new ActivityView("upload:upload-7", ActivityKind.UPLOAD, ActivityStatus.UPLOADING,
                        new ActivityProgress(2, 4, 0), null, null, null, now, now, null,
                        new ActivityActions(true, false, false), 0));
        verify(projection).reconcile(eq(7L), eq(ActivitySourceKind.UPLOAD),
                argThat(snapshot -> snapshot.get().equals(List.of(expected))));
    }
}
